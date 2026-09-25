package dev.nkap.server.provider;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import dev.nkap.core.money.Currency;
import dev.nkap.provider.ProviderId;
import dev.nkap.provider.mpesa.MpesaProfile;
import dev.nkap.server.provider.CredentialFileReader.CredentialFile;
import dev.nkap.server.provider.RereadMpesaProfile.Credential;
import dev.nkap.server.support.LogCapture;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.env.ConfigTreePropertySource;
import org.springframework.boot.env.ConfigTreePropertySource.Option;

/**
 * The use-time read and the decision ADR 0015 records: a rotated credential file is used without a
 * restart, an invalid or unreadable one keeps the last valid credentials in use, and that stale use
 * is reported once in the log and continuously by {@link RereadMpesaProfile#staleFor()}.
 *
 * <p>Files are written to a real directory and read through Spring's own config-tree reader, as in
 * production. Their modification times are set explicitly, so no test depends on a file system's
 * timestamp resolution. Every credential is a canary that must appear in no message and no log line.
 */
class RereadMpesaProfileTest {

    private static final String PASSKEY = "NKAP_PROVIDER_MPESA_KE_PASSKEY";
    private static final String CONSUMER_KEY = "NKAP_PROVIDER_MPESA_KE_CONSUMER_KEY";
    private static final String CONSUMER_SECRET = "NKAP_PROVIDER_MPESA_KE_CONSUMER_SECRET";

    private static final String PASSKEY_1 = "canary-passkey-one-7f3a";
    private static final String PASSKEY_2 = "canary-passkey-two-9c1e";
    private static final String KEY_1 = "canary-consumer-key-one-2b8d";
    private static final String SECRET_1 = "canary-consumer-secret-one-5e4f";
    private static final List<String> CANARIES = List.of(PASSKEY_1, PASSKEY_2, KEY_1, SECRET_1);

    private static final ProviderId KENYA = ProviderId.of("mpesa-ke");

    @TempDir
    Path secrets;

    private final MovableClock clock = new MovableClock(Instant.parse("2026-09-25T12:00:00Z"));
    private final AtomicInteger reads = new AtomicInteger();
    private Instant fileTime = Instant.parse("2026-09-25T11:00:00Z");
    private RereadMpesaProfile profile;

    @BeforeEach
    void startup() throws IOException {
        write(PASSKEY, PASSKEY_1);
        write(CONSUMER_KEY, KEY_1);
        write(CONSUMER_SECRET, SECRET_1);
        profile = reread(Map.of(Credential.PASSKEY, PASSKEY, Credential.CONSUMER_KEY, CONSUMER_KEY,
                Credential.CONSUMER_SECRET, CONSUMER_SECRET));
    }

    // --- rotation --------------------------------------------------------------------------

    @Test
    @DisplayName("a credential changed on disk is what the next use sees, without a restart")
    void a_rotated_file_is_used_without_a_restart() throws IOException {
        assertThat(profile.get().passkey()).isEqualTo(PASSKEY_1);

        write(PASSKEY, PASSKEY_2);

        assertThat(profile.get().passkey()).isEqualTo(PASSKEY_2);
        assertThat(profile.staleFor()).isZero();
    }

    @Test
    @DisplayName("a rotated file ending in one newline is trimmed exactly as at startup: the read is Spring's own")
    void a_trailing_newline_is_trimmed_as_at_startup() throws IOException {
        write(PASSKEY, PASSKEY_2 + "\n");

        assertThat(profile.get().passkey()).isEqualTo(PASSKEY_2);
        assertThat(profile.staleFor()).isZero();
    }

    @Test
    @DisplayName("with no modification time changed, the files are not read again: the gate is measured, not claimed")
    void unchanged_files_are_not_read_again() throws IOException {
        profile.get();
        int afterFirstLook = reads.get();

        for (int i = 0; i < 50; i++) {
            profile.get();
        }
        // Content changed, modification time put back: the gate, not the content, decides.
        FileTime before = Files.getLastModifiedTime(secrets.resolve(PASSKEY));
        Files.writeString(secrets.resolve(PASSKEY), PASSKEY_2);
        Files.setLastModifiedTime(secrets.resolve(PASSKEY), before);

        assertThat(profile.get().passkey()).isEqualTo(PASSKEY_1);
        assertThat(afterFirstLook).isEqualTo(3);
        assertThat(reads).hasValue(afterFirstLook);
    }

    @Test
    @DisplayName("only the credentials are re-read: the base URL, shortcode and currency stay the startup profile's")
    void only_the_credentials_are_reread() throws IOException {
        write(PASSKEY, PASSKEY_2);

        MpesaProfile now = profile.get();

        assertThat(now.baseUrl()).isEqualTo(URI.create("https://sandbox.safaricom.co.ke"));
        assertThat(now.businessShortCode()).isEqualTo("174379");
        assertThat(now.currency()).isEqualTo(Currency.KES);
    }

    @Test
    @DisplayName("a credential with no file is never read: the startup value stays, and the others still rotate")
    void a_credential_without_a_file_keeps_its_startup_value() throws IOException {
        profile = reread(Map.of(Credential.PASSKEY, PASSKEY));

        write(PASSKEY, PASSKEY_2);
        write(CONSUMER_KEY, "canary-consumer-key-two-that-is-not-bound");

        assertThat(profile.get().passkey()).isEqualTo(PASSKEY_2);
        assertThat(profile.get().consumerKey()).isEqualTo("startup-consumer-key");
    }

    // --- the decision ----------------------------------------------------------------------

    @ParameterizedTest(name = "U+{0}")
    @ValueSource(strings = {"00A0", "FEFF"})
    @DisplayName("a rotated value with an invisible leading character is rejected: the last valid one serves, one warning, the gauge leaves zero")
    void an_invisible_leading_character_keeps_the_last_valid_value(String codePoint) throws IOException {
        profile.get();
        String invisible = new String(Character.toChars(Integer.parseInt(codePoint, 16)));

        try (LogCapture log = new LogCapture(RereadMpesaProfile.class)) {
            write(PASSKEY, invisible + PASSKEY_2);
            clock.advance(Duration.ofSeconds(5));
            assertThat(profile.get().passkey()).as("the last valid passkey still serves").isEqualTo(PASSKEY_1);

            clock.advance(Duration.ofSeconds(40));
            for (int i = 0; i < 20; i++) {
                assertThat(profile.get().passkey()).isEqualTo(PASSKEY_1);
            }
            touch(PASSKEY);
            assertThat(profile.get().passkey()).isEqualTo(PASSKEY_1);

            assertThat(profile.staleFor()).isEqualTo(Duration.ofSeconds(40));
            List<ILoggingEvent> warnings = warnings(log);
            assertThat(warnings).as("one warning, on the transition only").hasSize(1);
            assertThat(warnings.get(0).getFormattedMessage())
                    .contains("mpesa-ke")
                    .contains(PASSKEY + ": passkey has leading whitespace or an invisible character")
                    .doesNotContain(invisible);
            assertNoCredentialIn(warnings);
        }
    }

    @Test
    @DisplayName("a credential file that disappears is treated as an invalid one: the last valid value serves, one warning, the gauge leaves zero")
    void a_missing_file_keeps_the_last_valid_value() throws IOException {
        profile.get();

        try (LogCapture log = new LogCapture(RereadMpesaProfile.class)) {
            Files.delete(secrets.resolve(CONSUMER_SECRET));
            clock.advance(Duration.ofSeconds(3));

            assertThat(profile.get().consumerSecret()).isEqualTo(SECRET_1);
            clock.advance(Duration.ofSeconds(7));
            assertThat(profile.staleFor()).isEqualTo(Duration.ofSeconds(7));
            assertThat(warnings(log)).singleElement()
                    .extracting(ILoggingEvent::getFormattedMessage).asString()
                    .contains(CONSUMER_SECRET + " could not be read");
            assertNoCredentialIn(warnings(log));
        }
    }

    @Test
    @DisplayName("an empty rotated file is rejected like a blank variable, and the last valid value serves")
    void an_empty_file_keeps_the_last_valid_value() throws IOException {
        profile.get();

        write(CONSUMER_KEY, "");

        assertThat(profile.get().consumerKey()).isEqualTo(KEY_1);
        clock.advance(Duration.ofSeconds(2));
        assertThat(profile.staleFor()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("recovery: a valid value after a rejected one is used, the gauge returns to zero, and nothing more is logged")
    void recovery_is_used_and_silent() throws IOException {
        profile.get();

        try (LogCapture log = new LogCapture(RereadMpesaProfile.class)) {
            write(PASSKEY, "﻿" + PASSKEY_2);
            profile.get();
            clock.advance(Duration.ofSeconds(30));
            assertThat(profile.staleFor()).isPositive();

            write(PASSKEY, PASSKEY_2);

            assertThat(profile.get().passkey()).isEqualTo(PASSKEY_2);
            assertThat(profile.staleFor()).isZero();
            assertThat(warnings(log)).as("the rejection only; recovery is the gauge's to report").hasSize(1);
        }
    }

    @Test
    @DisplayName("a second rejection after a recovery is a new transition, and warns once more")
    void a_later_rejection_warns_again() throws IOException {
        profile.get();

        try (LogCapture log = new LogCapture(RereadMpesaProfile.class)) {
            write(PASSKEY, " " + PASSKEY_2);
            profile.get();
            write(PASSKEY, PASSKEY_2);
            profile.get();
            write(PASSKEY, PASSKEY_1 + " ");
            profile.get();

            assertThat(warnings(log)).hasSize(2);
            assertThat(warnings(log).get(1).getFormattedMessage()).contains("trailing");
            assertNoCredentialIn(warnings(log));
        }
    }

    @Test
    @DisplayName("an unexpected failure while looking at the files is a rejection, not a thrown payment: the last valid value serves, one warning, the gauge leaves zero")
    void an_unexpected_failure_is_a_rejection() {
        // A file with no path: looking at it throws NullPointerException, which none of the
        // expected failure paths (an I/O error, a vanished file, an invalid value) produces.
        CredentialFile broken = new CredentialFile(PASSKEY, null, new CountingTree(secrets, reads));
        RereadMpesaProfile withBrokenFile = new RereadMpesaProfile(KENYA, startupProfile(),
                Map.of(Credential.PASSKEY, broken), clock);

        try (LogCapture log = new LogCapture(RereadMpesaProfile.class)) {
            assertThat(withBrokenFile.get().passkey()).as("the startup passkey still serves").isEqualTo(PASSKEY_1);
            clock.advance(Duration.ofSeconds(12));
            assertThat(withBrokenFile.get().passkey()).isEqualTo(PASSKEY_1);

            assertThat(withBrokenFile.staleFor()).isEqualTo(Duration.ofSeconds(12));
            assertThat(warnings(log)).singleElement()
                    .extracting(ILoggingEvent::getFormattedMessage).asString()
                    .contains("mpesa-ke")
                    .contains("nkap_credentials_stale_seconds");
            assertNoCredentialIn(warnings(log));
        }
    }

    @Test
    @DisplayName("an installation with no credential file never reads anything and is never stale")
    void no_files_means_nothing_to_reread() {
        RereadMpesaProfile fixed = new RereadMpesaProfile(KENYA, startupProfile(), Map.of(), clock);

        assertThat(fixed.rereads()).isFalse();
        assertThat(fixed.get()).isSameAs(fixed.get());
        assertThat(fixed.staleFor()).isZero();
    }

    // --- helpers ---------------------------------------------------------------------------

    private RereadMpesaProfile reread(Map<Credential, String> names) {
        CountingTree tree = new CountingTree(secrets, reads);
        Map<Credential, CredentialFile> files = new EnumMap<>(Credential.class);
        names.forEach((credential, name) -> files.put(credential, new CredentialFile(name, secrets.resolve(name), tree)));
        return new RereadMpesaProfile(KENYA, startupProfile(), files, clock);
    }

    private static MpesaProfile startupProfile() {
        return new MpesaProfile(URI.create("https://sandbox.safaricom.co.ke"), "174379", PASSKEY_1,
                "startup-consumer-key", "startup-consumer-secret", Currency.KES);
    }

    /** Writes a file and moves its modification time forward, as any real rewrite does. */
    private void write(String name, String content) throws IOException {
        Files.writeString(secrets.resolve(name), content);
        touch(name);
    }

    private void touch(String name) throws IOException {
        fileTime = fileTime.plusSeconds(1);
        Files.setLastModifiedTime(secrets.resolve(name), FileTime.from(fileTime));
    }

    private static List<ILoggingEvent> warnings(LogCapture log) {
        return log.events().stream().filter(e -> e.getLevel() == Level.WARN).toList();
    }

    private static void assertNoCredentialIn(List<ILoggingEvent> events) {
        for (ILoggingEvent event : events) {
            for (String canary : CANARIES) {
                assertThat(event.getFormattedMessage()).as("a credential in the log").doesNotContain(canary);
            }
            assertThat(event.getThrowableProxy()).as("no cause attached, so no cause's message").isNull();
        }
    }

    /** Spring's config-tree reader, as production builds it, counting every read. */
    private static final class CountingTree extends ConfigTreePropertySource {

        private final AtomicInteger reads;

        CountingTree(Path directory, AtomicInteger reads) {
            super("test tree", directory, Option.ALWAYS_READ, Option.AUTO_TRIM_TRAILING_NEW_LINE);
            this.reads = reads;
        }

        @Override
        public ConfigTreePropertySource.Value getProperty(String name) {
            reads.incrementAndGet();
            return super.getProperty(name);
        }
    }

    private static final class MovableClock extends Clock {

        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
