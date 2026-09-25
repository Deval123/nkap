package dev.nkap.server.provider;

import dev.nkap.provider.ProviderId;
import dev.nkap.provider.mpesa.MpesaProfile;
import dev.nkap.server.provider.CredentialFileReader.CredentialFile;
import dev.nkap.server.provider.CredentialFileReader.Stamp;
import dev.nkap.server.provider.CredentialFileReader.UnreadableCredentialException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One M-Pesa installation's profile, with its three credentials read again from their files
 * whenever those files change (issue #223, ADR 0015). What {@code MpesaAdapter} is handed in
 * place of a fixed profile, so a rotated Secret takes effect without a restart.
 *
 * <p><strong>Only the credentials are re-read.</strong> The passkey, the Consumer Key and the
 * Consumer Secret come from their files. The base URL, the shortcode and the currency are the
 * startup profile's and never change. {@code MpesaConfiguration.mpesaRoutings} builds the
 * installation's routing from its base URL and currency at startup, {@link ConfiguredAdapterRegistry}
 * takes the settlement currency from it, and {@code PaymentController} answers
 * {@code unserved-currency} from that. A re-read that could change either would leave the
 * adapter talking to one place while routing described another, and nothing would report it. The
 * adapter enforces the same rule on its side.
 *
 * <p><strong>A credential supplied as a variable is not re-read</strong>, because a process cannot
 * see its own environment change. Only one supplied by a file in the imported directory is.
 *
 * <p>Each call:
 * <ol>
 *   <li>compares each credential file's {@link CredentialFileReader.Stamp}, its real path,
 *       modification time and size, with the last one seen. If none changed, it returns the held
 *       profile without reading anything. This bounds how many copies of a credential the heap
 *       accumulates. A Kubernetes Secret update changes the real path, and the resolved file's
 *       time with it;</li>
 *   <li>otherwise reads the files and builds a profile from them and the startup fields. The
 *       validation is {@link MpesaProfile}'s own constructor, the same one startup ran, so a value
 *       refused at startup is refused here too;</li>
 *   <li>if that works, the new profile is held and returned;</li>
 *   <li>if it throws, or a file cannot be read, <strong>the last valid profile is kept and the
 *       payment is served with it</strong>. The candidate is rejected; the installation is not.
 *       ADR 0015 records why.</li>
 * </ol>
 *
 * <p><strong>Stale use is visible, not only logged.</strong> The first rejection logs one warning
 * naming the installation and the file, and, through {@link MpesaProfile}'s message, the field and
 * which end, never a value, its length or the character found. It is not repeated while the files
 * stay rejected. {@link #staleFor()} reports how long they have been, and
 * {@code CredentialStalenessMetrics} exports it as a gauge a deployer can alert on. Recovery logs
 * nothing: the gauge returning to zero is the signal. Do not add a second warning for it.
 *
 * <p><strong>It never throws.</strong> {@code MpesaAdapter} calls it on the payment's own path,
 * so a throw here would fail the payment, which is exactly what the decision above refuses. The
 * expected failures are each handled where they arise: a file that cannot be looked at, a file
 * that cannot be read, a value {@link MpesaProfile} refuses. {@link #get()} does not rely on
 * those three staying true. Any other {@link RuntimeException} is treated as a rejection too.
 * That has a cost: a programming error is masked as stale credentials instead of surfacing as a
 * failure. It is the better of the two outcomes: the installation keeps a known-good value, one
 * warning names the exception's type (never its message, which is not known to be free of a
 * credential), and the gauge says it is stale, rather than a payment failing.
 *
 * <p>At startup there is no last valid profile, so a bad value still stops the gateway, as it
 * always has. That asymmetry is the decision, not an accident of where the code sits.
 */
final class RereadMpesaProfile implements Supplier<MpesaProfile> {

    private static final Logger log = LoggerFactory.getLogger(RereadMpesaProfile.class);

    /** The three credentials, by the property {@code application.yml} binds and the profile's field name. */
    enum Credential {
        PASSKEY("passkey", "passkey"),
        CONSUMER_KEY("consumer-key", "consumerKey"),
        CONSUMER_SECRET("consumer-secret", "consumerSecret");

        final String property;
        final String field;

        Credential(String property, String field) {
            this.property = property;
            this.field = field;
        }
    }

    private final ProviderId id;
    private final Map<Credential, CredentialFile> files;
    private final Clock clock;

    private MpesaProfile held;
    /**
     * Each file's {@link Stamp} last seen, null until the first look. A file that cannot be looked
     * at maps to null, which equals itself, so a vanished file is rejected once and not again on
     * every call, and differs from any real stamp, so its return is seen.
     */
    private Map<Credential, Stamp> seen;
    /** When the files were first found rejected; null while the held profile is what they say. */
    private volatile Instant staleSince;

    RereadMpesaProfile(ProviderId id, MpesaProfile startup, Map<Credential, CredentialFile> files, Clock clock) {
        this.id = Objects.requireNonNull(id, "id");
        this.held = Objects.requireNonNull(startup, "startup");
        this.files = files.isEmpty() ? Map.of() : new EnumMap<>(files);
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    ProviderId id() {
        return id;
    }

    /** Whether any credential comes from a file, and so can change while the gateway runs. */
    boolean rereads() {
        return !files.isEmpty();
    }

    @Override
    public synchronized MpesaProfile get() {
        if (files.isEmpty()) {
            return held;
        }
        try {
            Map<Credential, Stamp> now = new HashMap<>();
            files.forEach((credential, file) -> now.put(credential, file.stamp()));
            if (now.equals(seen)) {
                return held;
            }
            seen = now;
            held = new MpesaProfile(held.baseUrl(), held.businessShortCode(),
                    current(Credential.PASSKEY, held.passkey()),
                    current(Credential.CONSUMER_KEY, held.consumerKey()),
                    current(Credential.CONSUMER_SECRET, held.consumerSecret()),
                    held.currency());
            staleSince = null;
        } catch (UnreadableCredentialException unreadable) {
            reject(unreadable.file() + " could not be read");
        } catch (IllegalArgumentException invalid) {
            reject(named(invalid.getMessage()));
        } catch (RuntimeException unexpected) {
            // Not one of the failures above: a defect, not a bad file. Named by its type only;
            // its message is not known to be free of a credential.
            reject("the credential files could not be checked (" + unexpected.getClass().getName() + ")");
        }
        return held;
    }

    /**
     * How long the credential files have been unreadable or invalid, zero while the held profile
     * is the one they describe. Looks at the files first, so it is current even when no payment
     * has asked since they changed.
     */
    Duration staleFor() {
        get();
        Instant since = staleSince;
        return since == null ? Duration.ZERO : Duration.between(since, clock.instant());
    }

    private String current(Credential credential, String startup) {
        CredentialFile file = files.get(credential);
        return file == null ? startup : file.read();
    }

    private void reject(String why) {
        if (staleSince != null) {
            return;
        }
        staleSince = clock.instant();
        String reason = why.endsWith(".") ? why.substring(0, why.length() - 1) : why;
        log.warn("M-Pesa installation {}: the credentials on disk were rejected, and payments keep using the last"
                + " valid ones. {}. Fix the file; the gauge nkap_credentials_stale_seconds stays above zero until"
                + " then, and a restart in this state fails.", id, reason);
    }

    /**
     * {@link MpesaProfile}'s refusal, which names the field and which end and never the value,
     * prefixed with the file that field came from.
     */
    private String named(String refusal) {
        for (Credential credential : Credential.values()) {
            CredentialFile file = files.get(credential);
            if (file != null && refusal.startsWith(credential.field + " ")) {
                return file.name() + ": " + refusal;
            }
        }
        return refusal;
    }
}
