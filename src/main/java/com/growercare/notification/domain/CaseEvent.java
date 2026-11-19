package com.growercare.notification.domain;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A case-service event as notification-service reads it (SPEC-NOT-02 D1): the envelope's id and type and the scalar
 * fields of {@code data}, as text. Ids are strings in the events (convention 2).
 */
public record CaseEvent(UUID eventId, String type, Map<String, String> data) {

    private static final Pattern ID = Pattern.compile("^[1-9][0-9]{0,18}$");

    /** The event cannot be processed as it is: never retried (it goes straight to the DLQ). */
    public static final class Malformed extends RuntimeException {
        public Malformed(String message) {
            super(message);
        }
    }

    public CaseEvent {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(type, "type");
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public long caseId() {
        return id("caseId");
    }

    /** A required id field. */
    public long id(String field) {
        var raw = data.get(field);
        if (raw == null || !ID.matcher(raw).matches()) {
            throw new Malformed("data." + field + " is missing or not an id");
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            throw new Malformed("data." + field + " is out of range");
        }
    }

    /** An optional text field, or null. */
    public String text(String field) {
        return data.get(field);
    }

    /** A required, non-blank text field. */
    public String requiredText(String field) {
        var value = data.get(field);
        if (value == null || value.isBlank()) {
            throw new Malformed("data." + field + " is missing");
        }
        return value;
    }
}
