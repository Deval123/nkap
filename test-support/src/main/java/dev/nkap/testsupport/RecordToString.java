package dev.nkap.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Checks a record's hand-written {@code toString()} against the record's own components, so a
 * credential-holding record cannot grow a component that is neither printed nor masked.
 *
 * <p>Once four copies ({@code server}, {@code provider-mtn}, {@code provider-mpesa},
 * {@code simulator-mpesa}), identical but for their package; one now (issue #214).
 */
public final class RecordToString {

    private RecordToString() {
    }

    /**
     * Every component is named by {@code toString()}, in declaration order. Those in
     * {@code printed} show their value, and those in {@code masked} show {@code marker}. The
     * two sets together are exactly the record's components, so a component added later fails
     * here until someone decides which set it belongs in.
     */
    public static void assertEveryComponentPrintedOrMasked(Record record, Set<String> printed, Set<String> masked,
                                                           String marker) {
        RecordComponent[] components = record.getClass().getRecordComponents();
        String[] names = Arrays.stream(components).map(RecordComponent::getName).toArray(String[]::new);
        Map<String, String> shown = pairs(record);

        assertThat(printed.size() + masked.size())
                .as("components classified: add a new one to the printed or the masked set")
                .isEqualTo(components.length);
        assertThat(shown.keySet()).as("components toString() names, in declaration order").containsExactly(names);
        for (RecordComponent component : components) {
            String name = component.getName();
            if (printed.contains(name)) {
                assertThat(shown.get(name)).as(name).isEqualTo(String.valueOf(valueOf(record, component)));
            } else {
                assertThat(masked).as("%s is neither printed nor masked", name).contains(name);
                assertThat(shown.get(name)).as(name).isEqualTo(marker);
            }
        }
    }

    /**
     * {@code toString()}'s top-level {@code name=value} pairs, in order. A value that is itself a
     * record, printed as {@code Name[...]}, stays whole.
     */
    private static Map<String, String> pairs(Record record) {
        String text = record.toString();
        assertThat(text).as("toString() shape").startsWith(record.getClass().getSimpleName() + "[").endsWith("]");
        String body = text.substring(text.indexOf('[') + 1, text.length() - 1);
        Map<String, String> pairs = new LinkedHashMap<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i <= body.length(); i++) {
            boolean end = i == body.length();
            char c = end ? ',' : body.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == ',' && depth == 0 && (end || body.startsWith(", ", i))) {
                String pair = body.substring(start, i);
                int equals = pair.indexOf('=');
                pairs.put(pair.substring(0, equals), pair.substring(equals + 1));
                start = i + 2;
            }
        }
        return pairs;
    }

    private static Object valueOf(Record record, RecordComponent component) {
        try {
            component.getAccessor().setAccessible(true);
            return component.getAccessor().invoke(record);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(component.getName(), e);
        }
    }
}
