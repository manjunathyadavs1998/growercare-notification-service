package com.growercare.notification.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * SPEC-NOT-02 D5/D6, R45/R46: the grower's message window for one case. The first notice is sent at once and opens a
 * 5-minute window; notices during the window (or while one waits for the end of quiet hours) are counted and released
 * together when due: as themselves when there is one, as {@code case.updates} when there are more.
 *
 * @param windowEndsAt until when a new notice is held back
 * @param pendingCount notices waiting (0 = none)
 * @param pending      the latest waiting notice (null when none)
 * @param dueAt        when the waiting notices are released (null when none)
 * @param batchSeq     number of released batches, so each release has its own idempotency key
 */
public record GrowerWindow(Instant windowEndsAt, int pendingCount, GrowerNotice pending, Instant dueAt, int batchSeq) {

    public static final Duration LENGTH = Duration.ofMinutes(5);

    /** What to do with a new notice: send it now, and the window afterwards. */
    public record Step(boolean sendNow, GrowerWindow next) { }

    public GrowerWindow {
        Objects.requireNonNull(windowEndsAt, "windowEndsAt");
        if (pendingCount < 0 || (pendingCount == 0) != (pending == null) || (pendingCount == 0) != (dueAt == null)) {
            throw new IllegalArgumentException("pending notices, the latest one and the due time go together");
        }
    }

    /** @param current the case's window, or null before its first notice */
    public static Step onNotice(GrowerWindow current, GrowerNotice notice, Instant now, ZoneId zone) {
        Objects.requireNonNull(notice, "notice");
        int seq = current == null ? 0 : current.batchSeq;
        boolean held = current != null && (current.pendingCount > 0 || now.isBefore(current.windowEndsAt));
        if (!held) {
            var earliest = QuietHours.earliestSend(now, zone);
            if (!earliest.isAfter(now)) {
                return new Step(true, new GrowerWindow(now.plus(LENGTH), 0, null, null, seq));
            }
            return new Step(false, new GrowerWindow(current == null ? now : current.windowEndsAt, 1, notice, earliest, seq));
        }
        var due = current.pendingCount > 0 ? current.dueAt
                : QuietHours.earliestSend(current.windowEndsAt.isAfter(now) ? current.windowEndsAt : now, zone);
        return new Step(false, new GrowerWindow(current.windowEndsAt, current.pendingCount + 1, notice, due, seq));
    }

    public boolean isDue(Instant now) {
        return pendingCount > 0 && !dueAt.isAfter(now);
    }

    /** The message for the waiting notices. */
    public GrowerNotice release() {
        if (pendingCount == 0) {
            throw new IllegalStateException("nothing is waiting");
        }
        return pendingCount == 1 ? pending : GrowerNotice.updates(pendingCount);
    }

    /** After a release at {@code now}: nothing waits and a new window starts. */
    public GrowerWindow released(Instant now) {
        return new GrowerWindow(now.plus(LENGTH), 0, null, null, batchSeq + 1);
    }

    /** The release failed (a dependency was down): try again at {@code at}. */
    public GrowerWindow retryAt(Instant at) {
        return new GrowerWindow(windowEndsAt, pendingCount, pending, at, batchSeq);
    }
}
