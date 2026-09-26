package dev.nkap.testsupport;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A web application with nothing in it, for testing {@link SimulatorUnderTest} without
 * depending on a simulator. What the helper guarantees about the address it binds does not
 * depend on what the application serves.
 */
@SpringBootApplication
class BareApplication {
}
