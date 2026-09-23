package dev.nkap.provider.mpesa;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The simulator's core and M-Pesa's face, booted on their own. The simulator image assembles
 * MTN's face alone — the core runs one face per application context — so there is no
 * deployable to start, and every consumer of this face declares its own.
 */
@SpringBootApplication(scanBasePackages = "dev.nkap.simulator")
class MpesaSimulatorApplication {
}
