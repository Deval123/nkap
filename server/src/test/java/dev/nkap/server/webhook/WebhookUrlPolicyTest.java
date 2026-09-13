package dev.nkap.server.webhook;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Issue #77's second defect: {@code provision(merchantId, url)} took any string. Written
 * first, per the plan — the refusal is the whole point, so it is the first thing checked.
 */
class WebhookUrlPolicyTest {

    @Test
    @DisplayName("provisioning an http:// endpoint is refused, and the message names the problem")
    void an_http_url_is_refused() {
        assertThatThrownBy(() -> WebhookUrlPolicy.requireAllowed("http://merchant.example/hooks", false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https")
                .hasMessageContaining("http://merchant.example/hooks");
    }

    @Test
    @DisplayName("provisioning an https:// endpoint is accepted")
    void an_https_url_is_accepted() {
        assertThatCode(() -> WebhookUrlPolicy.requireAllowed("https://merchant.example/hooks", false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("with the demo setting enabled, an http:// endpoint is accepted, and nothing else about the refusal changes")
    void the_demo_setting_allows_http() {
        assertThatCode(() -> WebhookUrlPolicy.requireAllowed("http://merchant.example/hooks", true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("with the demo setting enabled, an https:// endpoint is still accepted")
    void the_demo_setting_still_allows_https() {
        assertThatCode(() -> WebhookUrlPolicy.requireAllowed("https://merchant.example/hooks", true))
                .doesNotThrowAnyException();
    }
}
