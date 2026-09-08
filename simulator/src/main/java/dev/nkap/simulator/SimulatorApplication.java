package dev.nkap.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A fake MTN MoMo operator, in memory.
 *
 * <p>It exposes the Collections API surface and plays a scenario — a timeline of
 * what happens at each interaction point of a payment (ADR 0002). Scenarios are
 * selected by ordered rules declared over HTTP under {@code /_nkap/}; with no
 * rules, every payment gets the happy path (accepted, then {@code SUCCESSFUL} on
 * the next query).
 *
 * <p>The individual failure modes — latency, timeout then late success,
 * duplicate and out-of-order callbacks, status flapping, token expiry — are
 * separate issues, and each is now configuration rather than code.
 */
@SpringBootApplication
public class SimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}
