package dev.nkap.simulator.mpesa;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The core and M-Pesa's face, on their own. The deployable assembles MTN's face today, and
 * the core runs one face per application context, so this face's tests boot it here.
 */
@SpringBootApplication(scanBasePackages = "dev.nkap.simulator")
class MpesaSimulatorTestApplication {
}
