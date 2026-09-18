package dev.nkap.server.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * {@code --nkap.apikey.create} and {@code --nkap.apikey.revoke} passed together used to let
 * create win silently: the operator saw a key created, exit code 0, and could believe a key
 * had been revoked when it had not. For a command whose whole point is the 3 a.m. case, that
 * is the wrong failure mode — this asserts the combination is rejected outright instead.
 */
class ApiKeyProvisioningRunnerTest {

    @Test
    @DisplayName("create and revoke together are rejected, naming both options, with a non-zero exit and no store call")
    void create_and_revoke_together_is_rejected() {
        ApiKeyStore keys = mock(ApiKeyStore.class);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ApiKeyProvisioningRunner runner = new ApiKeyProvisioningRunner(keys, new PrintStream(buffer));

        runner.run(new DefaultApplicationArguments(
                "--nkap.apikey.create", "--nkap.apikey.merchant=acme",
                "--nkap.apikey.revoke", "--nkap.apikey.id=" + UUID.randomUUID()));

        assertThat(runner.getExitCode()).as("neither option wins; the command fails").isNotZero();
        assertThat(buffer.toString())
                .as("the message names both options, not a generic error")
                .contains("--nkap.apikey.create")
                .contains("--nkap.apikey.revoke");
        // Neither a key was created nor one revoked -- the rejection happens before either
        // reaches the store, not after one of them already ran.
        verifyNoInteractions(keys);
    }
}
