package dev.nkap.simulator;

import java.util.UUID;

/**
 * A reference id is a UUID and its textual form is not significant: MTN clients
 * send it upper- or lower-case interchangeably. Everything stores and looks it
 * up in one canonical form so a POST and a later GET agree. A value that is not
 * a UUID is returned untouched and falls through to a 404.
 */
final class References {

    private References() {
    }

    static String canonical(String reference) {
        try {
            return UUID.fromString(reference).toString();
        } catch (IllegalArgumentException | NullPointerException e) {
            return reference;
        }
    }
}
