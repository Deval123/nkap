package dev.nkap.simulator.mpesa;

import dev.nkap.simulator.PaymentIdentity;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/**
 * How Safaricom identifies an STK Push: by a {@code CheckoutRequestID} it mints and returns
 * in its answer to the submission. The {@code AccountReference} the caller chose comes back
 * in nothing and is a key to nothing; two submissions carrying the same one were both
 * accepted as independent payments — <strong>observed</strong> 2026-09-18.
 *
 * <p>The identifier's shape, as far as it is known: {@code ws_CO_}, then the submission's
 * Nairobi local time as {@code ddMMyyyyHHmmss} — <strong>observed</strong> three times,
 * 2026-09-18, 2026-09-22 and 2026-09-23 — then ten digits. The page records the ten digits
 * without explaining them; every sample ends in the test MSISDN's last nine digits, after one
 * digit that varied ({@code 2}, {@code 7}). That reading is <strong>modelled</strong>: this
 * face writes a rotating digit and then the last nine digits of the submission's
 * {@code PhoneNumber}. Nobody should parse one, and the page says so.
 *
 * <p>The rotating digit also keeps two submissions from the same phone in the same second
 * apart — up to ten of them. An eleventh would repeat an identity, which the core refuses
 * loudly rather than let two payments answer to one.
 */
@Component
public class MpesaPaymentIdentity implements PaymentIdentity {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("ddMMyyyyHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Clock clock;
    private final AtomicInteger rotation = new AtomicInteger();

    public MpesaPaymentIdentity() {
        this(Clock.systemUTC());
    }

    MpesaPaymentIdentity(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Minter mintedBy() {
        return Minter.OPERATOR;
    }

    @Override
    public Repeat onRepeat() {
        return Repeat.NOT_DEDUPLICATED;
    }

    /** Identities are compared exactly: nothing observed says Safaricom folds their case. */
    @Override
    public String canonical(String identity) {
        return identity;
    }

    @Override
    public String mint(String msisdn) {
        int digit = Math.floorMod(rotation.getAndIncrement(), 10);
        return prefix() + digit + lastNineDigits(msisdn);
    }

    /** A {@code CheckoutRequestID} of the same shape whose ten digits no phone number gave. */
    @Override
    public String neverSubmitted() {
        StringBuilder tail = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            tail.append(RANDOM.nextInt(10));
        }
        return prefix() + tail;
    }

    /**
     * The {@code MerchantRequestID} Safaricom returns beside a {@code CheckoutRequestID}.
     * Its shape follows the one sample the page quotes, {@code 5dbd-4f93-a478-41cdaf2b9acd86873}:
     * three groups of four hex digits, then twelve hex digits and five decimal ones. That it
     * is <em>derived</em> from the {@code CheckoutRequestID} is <strong>modelled</strong>: the
     * real one is minted independently, but deriving it lets the submission's answer, every
     * query and every callback agree on it without the face keeping a memory of its own.
     */
    public static String merchantRequestId(String checkoutRequestId) {
        UUID seed = UUID.nameUUIDFromBytes(checkoutRequestId.getBytes(StandardCharsets.UTF_8));
        String hex = Long.toHexString(seed.getMostSignificantBits()) + Long.toHexString(seed.getLeastSignificantBits());
        hex = (hex + "0".repeat(24)).substring(0, 24);
        int decimal = Math.floorMod(checkoutRequestId.hashCode(), 100_000);
        return hex.substring(0, 4) + "-" + hex.substring(4, 8) + "-" + hex.substring(8, 12) + "-"
                + hex.substring(12, 24) + String.format("%05d", decimal);
    }

    private String prefix() {
        return "ws_CO_" + ZonedDateTime.now(clock).withZoneSameInstant(NAIROBI).format(STAMP);
    }

    private static String lastNineDigits(String msisdn) {
        String digits = msisdn == null ? "" : msisdn.replaceAll("\\D", "");
        String padded = "0".repeat(9) + digits;
        return padded.substring(padded.length() - 9);
    }
}
