package dev.nkap.simulator.mpesa.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A fake Safaricom Daraja operator, in memory: the neutral core ({@code simulator-core})
 * assembled with M-Pesa's face ({@code simulator-mpesa}). {@code simulator}'s
 * {@code SimulatorApplication} is the same assembly with MTN's face.
 *
 * <p>It plays STK Push, Collections only, and a scenario declared under {@code /_nkap/} or,
 * before the first request, from a file ({@link ScenarioFileLoader}); with no rules, every
 * payment gets the happy path. What of it is observed of Safaricom and what is modelled is
 * {@code docs/providers/m-pesa.md}'s to say, not this class's.
 *
 * <p>Scans {@code dev.nkap.simulator} rather than its own package, because the core's beans
 * live there and the face's in {@code dev.nkap.simulator.mpesa}; the two faces share those
 * packages, which is why only one face's jar may ever be on this application's classpath.
 */
@SpringBootApplication(scanBasePackages = "dev.nkap.simulator")
public class MpesaSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MpesaSimulatorApplication.class, args);
    }
}
