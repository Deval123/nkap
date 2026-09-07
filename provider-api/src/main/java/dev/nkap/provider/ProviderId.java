package dev.nkap.provider;

import java.util.Objects;
import java.util.regex.Pattern;

/** The short, stable identifier of a provider — {@code mtn}, {@code orange}, {@code wave}. */
public record ProviderId(String value) {

    private static final Pattern SHAPE = Pattern.compile("^[a-z][a-z0-9-]{1,31}$");

    public ProviderId {
        Objects.requireNonNull(value, "value");
        if (!SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Provider id '" + value + "' must be lowercase letters, digits and hyphens");
        }
    }

    public static ProviderId of(String value) {
        return new ProviderId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
