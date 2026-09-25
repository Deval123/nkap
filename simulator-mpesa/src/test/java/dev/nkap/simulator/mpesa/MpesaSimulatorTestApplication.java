package dev.nkap.simulator.mpesa;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The core and M-Pesa's face, on their own. The deployable, {@code simulator-mpesa-app}, is
 * downstream of this module, so this face's tests boot it here.
 */
@SpringBootApplication(scanBasePackages = "dev.nkap.simulator")
class MpesaSimulatorTestApplication {
}
