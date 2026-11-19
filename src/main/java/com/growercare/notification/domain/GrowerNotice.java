package com.growercare.notification.domain;

import java.util.Objects;

/**
 * What the grower is told (SPEC-NOT-02 D2, D7): a template, the status for {@code case.status}, the number of merged
 * events for {@code case.updates}. Never any personal or case detail.
 */
public record GrowerNotice(String template, String statusCode, int count) {

    public static final String CREATED = "case.created";
    public static final String REPLY = "case.reply";
    public static final String STATUS = "case.status";
    public static final String CLOSED = "case.closed";
    public static final String UPDATES = "case.updates";

    public GrowerNotice {
        Objects.requireNonNull(template, "template");
        if (STATUS.equals(template) != (statusCode != null)) {
            throw new IllegalArgumentException("a status code goes with case.status only");
        }
        if (UPDATES.equals(template) ? count < 2 : count != 1) {
            throw new IllegalArgumentException("case.updates merges two or more events; every other notice is one");
        }
    }

    public static GrowerNotice created() {
        return new GrowerNotice(CREATED, null, 1);
    }

    public static GrowerNotice reply() {
        return new GrowerNotice(REPLY, null, 1);
    }

    public static GrowerNotice status(String statusCode) {
        return new GrowerNotice(STATUS, Objects.requireNonNull(statusCode, "statusCode"), 1);
    }

    public static GrowerNotice closed() {
        return new GrowerNotice(CLOSED, null, 1);
    }

    public static GrowerNotice updates(int count) {
        return new GrowerNotice(UPDATES, null, count);
    }
}
