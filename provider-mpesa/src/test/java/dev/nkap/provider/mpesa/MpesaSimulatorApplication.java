package dev.nkap.provider.mpesa;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The simulator's core and M-Pesa's face, booted on their own. {@code simulator-mpesa-app} is
 * a deployable of the same assembly, but this module depends on the face alone, at test scope,
 * and booting the deployable instead is #214's question, not settled here.
 */
@SpringBootApplication(scanBasePackages = "dev.nkap.simulator")
class MpesaSimulatorApplication {
}
