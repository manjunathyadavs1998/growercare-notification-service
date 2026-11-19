package com.growercare.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * SPEC-NOT-02 D9 / R51: the batching window and whether quiet hours apply. The defaults are the spec's (5 minutes, quiet
 * hours on); the local stack shortens the window and switches quiet hours off so journeys run in seconds at any hour.
 */
public record NoticePolicy(Duration window, boolean quietHours) {

    static final Duration MAX_WINDOW = Duration.ofHours(1);          // before DEFAULT: its constructor reads it
    public static final NoticePolicy DEFAULT = new NoticePolicy(Duration.ofMinutes(5), true);

    public NoticePolicy {
        Objects.requireNonNull(window, "window");
        if (window.isNegative() || window.isZero() || window.compareTo(MAX_WINDOW) > 0) {
            throw new IllegalArgumentException("the batching window must be more than 0 and at most 1 hour, was " + window);
        }
    }

    /** {@code at} itself when quiet hours are off; otherwise {@link QuietHours#earliestSend}. */
    public Instant earliestSend(Instant at, ZoneId zone) {
        return quietHours ? QuietHours.earliestSend(at, zone) : at;
    }
}
