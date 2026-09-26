package dev.nkap.simulator.mpesa;

import dev.nkap.testsupport.RecordToString;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The two records {@link MpesaReceived} keeps hold credentials, so each is held to the rule every
 * such record in this repository is: every component is printed in clear or masked, and one added
 * later fails here until it is classified. {@code MpesaReceivedTest} checks that no credential
 * appears in either {@code toString()}; this checks that nothing escapes the classification.
 */
class MpesaReceivedRecordsTest {

    private static final String MASKED = "***";

    @Test
    @DisplayName("every TokenRequest component is accounted for in toString(): printed in clear or masked by a constant")
    void every_token_request_component_is_printed_or_masked() {
        RecordToString.assertEveryComponentPrintedOrMasked(
                new MpesaReceived.TokenRequest("canary-consumer-key-5f1e", "canary-consumer-secret-2a7c"),
                Set.of(), Set.of("consumerKey", "consumerSecret"), MASKED);
    }

    @Test
    @DisplayName("every Submission component is accounted for in toString(): printed in clear or masked by a constant")
    void every_submission_component_is_printed_or_masked() {
        RecordToString.assertEveryComponentPrintedOrMasked(
                new MpesaReceived.Submission("174379", "20260925183012", "Y2FuYXJ5LXBhc3N3b3Jk"),
                Set.of("businessShortCode", "timestamp"), Set.of("password"), MASKED);
    }
}
