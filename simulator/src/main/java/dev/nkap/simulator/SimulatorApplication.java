package dev.nkap.simulator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A fake MTN MoMo operator, in memory.
 *
 * <p>This is the skeleton only: it exposes the Collections API surface and plays
 * one hard-coded default (a request succeeds on its first query). Latency,
 * timeouts, duplicate and out-of-order callbacks, status flapping and token
 * expiry are separate issues, each introduced through the scenario mechanism
 * designed in issue #2.
 */
@SpringBootApplication
public class SimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(SimulatorApplication.class, args);
    }
}
