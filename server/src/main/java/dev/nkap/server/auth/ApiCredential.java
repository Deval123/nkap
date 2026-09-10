package dev.nkap.server.auth;

import java.util.UUID;

/**
 * The caller, resolved from a valid API key: which merchant they are, and whether the key
 * is an admin key.
 *
 * <p>This is the identity the gateway <strong>established</strong>. Every place that used to
 * read {@code merchantId} from the request body reads {@link #merchantId()} here instead —
 * the value that scopes idempotency, names the {@code merchant:<id>:payable} account, and
 * gates a read. {@link #admin()} gates operator-wide data (the statement report); it is one
 * boolean, not a role system.
 */
public record ApiCredential(UUID keyId, String merchantId, boolean admin) {
}
