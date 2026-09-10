package dev.nkap.server.auth;

import java.util.Optional;

/**
 * Where API keys live. Two operations, and deliberately no third:
 *
 * <ul>
 *   <li>{@link #authenticate} — presented a token, return the caller it identifies, or
 *       nothing. Called on every authenticated request.</li>
 *   <li>{@link #provision} — mint a key for a merchant. Called only by the host-side
 *       command, never from a route.</li>
 * </ul>
 *
 * <p>There is <strong>no method that returns a key</strong>. The store keeps only a hash;
 * a store that could hand back a token is a store that leaks every token the moment it is
 * read. The plaintext exists for exactly as long as {@link #provision} takes to return it.
 */
public interface ApiKeyStore {

    /**
     * The caller {@code presentedToken} identifies, if the token matches a stored key.
     * Records the use ({@code last_used_at}) as a side effect.
     */
    Optional<ApiCredential> authenticate(String presentedToken);

    /**
     * Creates a key for {@code merchantId} ({@code admin} gating operator-wide data) and
     * returns it <strong>once</strong>. Only the hash is stored.
     */
    Provisioned provision(String merchantId, boolean admin, String label);

    /**
     * Creates a key whose token is exactly {@code token}, for a caller that needs a known
     * value — the demo, and tests. <strong>Idempotent for a given token</strong>: if a key
     * with that token already exists, it is returned unchanged, so re-running the demo's
     * key-provisioning step does not fail. The token still must not be one anyone would
     * mistake for production; that is the caller's responsibility.
     */
    Provisioned provisionWithToken(String token, String merchantId, boolean admin, String label);

    /** A freshly minted key: the caller it authenticates, and the token, shown this once and never again. */
    record Provisioned(ApiCredential credential, String token) {
    }
}
