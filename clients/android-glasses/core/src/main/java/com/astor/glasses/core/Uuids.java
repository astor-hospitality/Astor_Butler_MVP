package com.astor.glasses.core;

import java.util.UUID;

final class Uuids {
    private Uuids() { }

    /** A UUID in its canonical 36-character form in any letter case; anything else is null. */
    static UUID parse(Object value) {
        if (!(value instanceof String)) return null;
        String text = (String) value;
        // UUID.fromString also accepts shortened groups such as "1-1-1-1-1"; the contract does not.
        if (!text.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) return null;
        return UUID.fromString(text);
    }
}
