package dev.nkap.server.provider;

import dev.nkap.provider.ProviderId;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * How long each installation whose credentials are re-read from files has been running on stale
 * ones: zero while what it uses is what its files say, and the time since the files were first
 * found unreadable or invalid otherwise (ADR 0015).
 *
 * <p>Only installations with a credential supplied as a file are listed. One configured by
 * variables alone cannot go stale: a process cannot see its own environment change.
 *
 * <p>Durations and provider ids, nothing else: no credential, file content or path is reachable
 * from here, so a metric built on this cannot carry one.
 */
public final class CredentialStaleness {

    private final Map<ProviderId, Supplier<Duration>> byInstallation;

    public CredentialStaleness(Map<ProviderId, Supplier<Duration>> byInstallation) {
        this.byInstallation = new LinkedHashMap<>(byInstallation);
    }

    /** The installations whose credentials can change while the gateway runs. */
    public Set<ProviderId> installations() {
        return byInstallation.keySet();
    }

    /** How long {@code installation}'s credentials on disk have been rejected; zero when they are in use. */
    public Duration staleFor(ProviderId installation) {
        Supplier<Duration> staleness = byInstallation.get(Objects.requireNonNull(installation, "installation"));
        if (staleness == null) {
            throw new IllegalArgumentException("no installation re-reads its credentials under " + installation);
        }
        return staleness.get();
    }
}
