package dev.nkap.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ResolutionTest {

    @Test
    @DisplayName("of() rejects an empty set — an adapter must declare at least one way to resolve a lost submission")
    void of_rejects_an_empty_set() {
        assertThrows(IllegalArgumentException.class, Resolution::of);
    }

    @Test
    @DisplayName("of() accepts one member")
    void of_accepts_one_member() {
        assertEquals(Set.of(Resolution.QUERY), Resolution.of(Resolution.QUERY));
    }

    @Test
    @DisplayName("of() accepts both members")
    void of_accepts_both_members() {
        assertEquals(Set.of(Resolution.QUERY, Resolution.CALLBACK),
                Resolution.of(Resolution.QUERY, Resolution.CALLBACK));
    }
}
