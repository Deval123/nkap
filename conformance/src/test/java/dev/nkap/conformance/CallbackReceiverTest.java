package dev.nkap.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.nkap.provider.RawCallback;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link CallbackReceiver}'s two promises, checked without an operator: what arrives is handed
 * back as it arrived, and only to the route it was sent to.
 */
class CallbackReceiverTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private static CallbackReceiver receiver;

    @BeforeAll
    static void start() {
        receiver = new CallbackReceiver();
    }

    @AfterAll
    static void stop() {
        receiver.close();
    }

    @Test
    @DisplayName("a callback is handed back with its body and headers as they arrived")
    void a_callback_is_handed_back_as_it_arrived() throws Exception {
        CallbackReceiver.Route route = receiver.open();
        try {
            deliver(route.url(), "{\"status\":\"SUCCESSFUL\"}");

            RawCallback delivered = route.poll(Duration.ofSeconds(5));
            assertEquals("{\"status\":\"SUCCESSFUL\"}", delivered.body());
            assertEquals("operator", delivered.headers().get("X-sent-by"));
        } finally {
            route.close();
        }
    }

    @Test
    @DisplayName("a callback that arrives for a closed route lands in no other route")
    void a_late_callback_lands_in_no_other_route() throws Exception {
        CallbackReceiver.Route closed = receiver.open();
        CallbackReceiver.Route open = receiver.open();
        try {
            closed.close();
            deliver(closed.url(), "{\"late\":true}");

            assertThrows(IllegalStateException.class, () -> open.poll(Duration.ofMillis(300)));
        } finally {
            open.close();
        }
    }

    private static void deliver(String url, String body) throws Exception {
        HttpResponse<Void> response = HTTP.send(HttpRequest.newBuilder(URI.create(url))
                .header("X-Sent-By", "operator")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(200, response.statusCode());
    }
}
