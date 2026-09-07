package dev.nkap.provider;

import java.util.Map;
import java.util.Objects;

/** An unverified webhook exactly as it arrived: headers, body, and nothing inferred. */
public record RawCallback(Map<String, String> headers, String body) {

    public RawCallback {
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        Objects.requireNonNull(body, "body");
    }
}
