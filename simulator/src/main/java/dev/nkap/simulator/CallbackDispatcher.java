package dev.nkap.simulator;

import dev.nkap.simulator.scenario.CallbackSpec;
import dev.nkap.simulator.scenario.CallbackTarget;
import dev.nkap.simulator.scenario.MomoStatus;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Delivers the callbacks a resolved scenario declares, and remembers every
 * attempt so a test can assert what was sent without standing up a receiver.
 *
 * <p>The simulator sends exactly what the scenario says: <strong>no retries</strong>.
 * A retry would be a scenario feature, not a default. A failed delivery is
 * recorded and logged, and that is all.
 */
@Component
public class CallbackDispatcher {

    private static final Logger log = LoggerFactory.getLogger(CallbackDispatcher.class);

    /**
     * One delivery attempt, as {@code GET /_nkap/callbacks/{referenceId}} reports
     * it: when it went out, to which URL, for which reference and with which
     * status, whether the receiver answered at all, its HTTP status if it did,
     * and the error if it did not.
     */
    public record Attempt(
            Instant at,
            String url,
            String targetReferenceId,
            MomoStatus status,
            boolean answered,
            Integer responseStatus,
            String error) {}

    private final ScheduledExecutorService scheduler;
    private final RestClient http;

    /**
     * Maximum number of attempts retained per submission reference to prevent
     * unbounded memory growth in long-running simulator instances (#16).
     */
    static final int MAX_ATTEMPTS_PER_REFERENCE = 100;

    /**
     * Maximum number of submission references retained in memory.
     * Oldest references are evicted first once this bound is exceeded.
     */
    static final int MAX_REFERENCES = 1000;

    /** Attempts keyed by the reference the client submitted with, bounded by MAX_REFERENCES. */
    private final Map<String, List<Attempt>> attempts = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<Attempt>> eldest) {
                    return size() > MAX_REFERENCES;
                }
            }
    );

    CallbackDispatcher() {
        ThreadFactory threads = runnable -> {
            Thread t = new Thread(runnable, "callback-dispatcher");
            t.setDaemon(true);
            return t;
        };
        this.scheduler = Executors.newScheduledThreadPool(2, threads);

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(Duration.ofSeconds(2));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Schedules every delivery the given callbacks declare, relative to now.
     * Call this <strong>when the scenario resolves, before the submit delay is
     * applied</strong>: that ordering is what lets a callback with
     * {@code after: PT0S} reach the client while its submit call is still
     * waiting on a delay — issue #6, the callback that arrives before the submit
     * response.
     *
     * <p>With no {@code url} there is nothing to do; that is not an error — most
     * scenarios have no callbacks — so it is logged at INFO, not WARN.
     */
    public void schedule(String submissionReferenceId, String amount, String currency,
                         List<CallbackSpec> callbacks, String url) {
        if (callbacks.isEmpty()) {
            return;
        }
        if (url == null || url.isBlank()) {
            log.info("reference {} resolved to a scenario with {} callback(s) but no callback URL "
                    + "(X-Callback-Url header or control-plane callbackUrl); delivering none",
                    submissionReferenceId, callbacks.size());
            return;
        }
        for (CallbackSpec spec : callbacks) {
            String targetReferenceId = switch (spec.target()) {
                case SAME_REFERENCE -> submissionReferenceId;
                case UNKNOWN_REFERENCE -> UUID.randomUUID().toString();
            };
            for (int i = 0; i < spec.times(); i++) {
                Duration delay = spec.after().plus(spec.every().multipliedBy(i));
                scheduler.schedule(
                        () -> deliver(submissionReferenceId, url, targetReferenceId, amount, currency, spec.status()),
                        Math.max(delay.toMillis(), 0), TimeUnit.MILLISECONDS);
            }
        }
        log.info("scheduled callback delivery for reference {} to {}", submissionReferenceId, url);
    }

    /** Attempts recorded for a submission reference, oldest first; empty if none. */
    public List<Attempt> attemptsFor(String submissionReferenceId) {
        return List.copyOf(attempts.getOrDefault(submissionReferenceId, List.of()));
    }

    /** Forgets every recorded attempt. Called by {@code DELETE /_nkap/state}. */
    public void clear() {
        attempts.clear();
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    private void deliver(String submissionReferenceId, String url, String targetReferenceId,
                         String amount, String currency, MomoStatus status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("referenceId", targetReferenceId);
        body.put("status", status.name());
        if (amount != null) {
            body.put("amount", amount);
        }
        if (currency != null) {
            body.put("currency", currency);
        }
        body.put("financialTransactionId", financialTransactionId(targetReferenceId));
        if (status == MomoStatus.FAILED) {
            body.put("reason", "SIMULATED_FAILURE");
        }

        Instant at = Instant.now();
        boolean answered = false;
        Integer responseStatus = null;
        String error = null;
        try {
            ResponseEntity<Void> response = http.post().uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .toBodilessEntity();
            answered = true;
            responseStatus = response.getStatusCode().value();
        } catch (RestClientResponseException e) {
            answered = true;
            responseStatus = e.getStatusCode().value();
            error = e.getStatusText();
        } catch (Exception e) {
            error = e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : "");
        }

        record(submissionReferenceId, new Attempt(at, url, targetReferenceId, status, answered, responseStatus, error));

        if (answered && responseStatus != null && responseStatus < 300) {
            log.info("callback for {} delivered to {} ({})", targetReferenceId, url, responseStatus);
        } else {
            log.warn("callback for {} to {} not delivered: {}", targetReferenceId, url,
                    error != null ? error : responseStatus);
        }
    }

    void recordAttempt(String submissionReferenceId, Attempt attempt) {
        attempts.compute(submissionReferenceId, (k, list) -> {
            List<Attempt> current = (list != null) ? list : new CopyOnWriteArrayList<>();
            current.add(attempt);
            while (current.size() > MAX_ATTEMPTS_PER_REFERENCE) {
                current.remove(0);
            }
            return current;
        });
    }

    private void record(String submissionReferenceId, Attempt attempt) {
        recordAttempt(submissionReferenceId, attempt);
    }

    /**
     * A stand-in for MTN's numeric transaction id. Derived from the reference so
     * a scenario stays deterministic across runs (ADR 0002), not random.
     */
    private static String financialTransactionId(String reference) {
        return Long.toString(Integer.toUnsignedLong(reference.hashCode()));
    }
}
