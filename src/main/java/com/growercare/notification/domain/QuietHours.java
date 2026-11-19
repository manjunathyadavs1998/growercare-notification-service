package com.growercare.notification.domain;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/** SPEC-NOT-02 D6 / R46: no grower messages from 21:00 to 07:00 in the country's time zone. */
public final class QuietHours {

    public static final LocalTime START = LocalTime.of(21, 0);
    public static final LocalTime END = LocalTime.of(7, 0);

    private QuietHours() {
    }

    /** {@code at} itself outside quiet hours; otherwise the next 07:00 local time. 21:00 is quiet, 07:00 is not. */
    public static Instant earliestSend(Instant at, ZoneId zone) {
        var local = at.atZone(zone);
        var time = local.toLocalTime();
        if (!time.isBefore(START)) {
            return local.toLocalDate().plusDays(1).atTime(END).atZone(zone).toInstant();
        }
        if (time.isBefore(END)) {
            return local.toLocalDate().atTime(END).atZone(zone).toInstant();
        }
        return at;
    }
}
