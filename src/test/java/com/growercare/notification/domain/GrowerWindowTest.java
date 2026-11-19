package com.growercare.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-NOT-02 D5/D6, R45/R46/R48: the grower's window per case (NOT-E44, NOT-E45). */
class GrowerWindowTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static Instant ist(String localDateTime) {
        return LocalDateTime.parse(localDateTime).atZone(IST).toInstant();
    }

    /** What happens to each notice, when the held ones are due, and the message then released ("template:count"). */
    record Expectation(List<Boolean> sentAtOnce, String dueAt, String released) { }

    static Stream<Arguments> sequences() {
        return Stream.of(
                argumentSet("NOT-E44 created 10:00, reply 10:01, status 10:03 → 10:00 at once, '2 updates' at 10:05",
                        List.of("2026-11-19T10:00:00", "2026-11-19T10:01:00", "2026-11-19T10:03:00"),
                        new Expectation(List.of(true, false, false), "2026-11-19T10:05:00", "case.updates:2")),
                argumentSet("NOT-E44 one held notice is released as itself",
                        List.of("2026-11-19T10:00:00", "2026-11-19T10:04:59"),
                        new Expectation(List.of(true, false), "2026-11-19T10:05:00", "case.reply:1")),
                argumentSet("NOT-E44 after the window a notice goes at once again",
                        List.of("2026-11-19T10:00:00", "2026-11-19T10:05:00"),
                        new Expectation(List.of(true, true), null, null)),
                argumentSet("NOT-E45 reply at 22:30 IST waits until 07:00",
                        List.of("2026-11-19T22:30:00"),
                        new Expectation(List.of(false), "2026-11-20T07:00:00", "case.reply:1")),
                argumentSet("NOT-E45 a window ending in quiet hours is released at 07:00",
                        List.of("2026-11-19T20:58:00", "2026-11-19T21:01:00"),
                        new Expectation(List.of(true, false), "2026-11-20T07:00:00", "case.reply:1")),
                argumentSet("NOT-E45 notices during quiet hours are merged at 07:00",
                        List.of("2026-11-19T22:30:00", "2026-11-20T01:00:00", "2026-11-20T05:00:00"),
                        new Expectation(List.of(false, false, false), "2026-11-20T07:00:00", "case.updates:3")));
    }

    @ParameterizedTest
    @MethodSource("sequences")
    void notices(List<String> times, Expectation expected) {
        GrowerWindow window = null;
        var sent = new ArrayList<Boolean>();
        for (var time : times) {
            var step = GrowerWindow.onNotice(window, GrowerNotice.reply(), ist(time), IST);
            sent.add(step.sendNow());
            window = step.next();
        }
        assertThat(sent).isEqualTo(expected.sentAtOnce());
        if (expected.dueAt() == null) {
            assertThat(window.pendingCount()).isZero();
            return;
        }
        var due = ist(expected.dueAt());
        assertThat(window.dueAt()).isEqualTo(due);
        assertThat(window.isDue(due.minusSeconds(1))).isFalse();
        assertThat(window.isDue(due)).isTrue();
        var released = window.release();
        assertThat(released.template() + ":" + released.count()).isEqualTo(expected.released());
        var after = window.released(due);
        assertThat(after.pendingCount()).isZero();
        assertThat(after.batchSeq()).isEqualTo(window.batchSeq() + 1);
        assertThat(after.windowEndsAt()).isEqualTo(due.plus(GrowerWindow.LENGTH));
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
                argumentSet("NOT-R45 pending without a due time", (Runnable) () -> new GrowerWindow(Instant.EPOCH, 1, GrowerNotice.reply(), null, 0)),
                argumentSet("NOT-R45 nothing waiting cannot be released", (Runnable) () -> new GrowerWindow(Instant.EPOCH, 0, null, null, 0).release()),
                argumentSet("NOT-R45 case.updates merges two or more", (Runnable) () -> GrowerNotice.updates(1)),
                argumentSet("NOT-R43 case.status needs the status", (Runnable) () -> new GrowerNotice(GrowerNotice.STATUS, null, 1)));
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void invariants(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfAny(IllegalArgumentException.class, IllegalStateException.class);
    }

    static Stream<Arguments> retries() {
        return Stream.of(argumentSet("NOT-R48 a failed 07:00 release retries a minute later", "2026-11-20T07:01:00"));
    }

    /** A failed release keeps its notices and batch number; only the due time moves. */
    @ParameterizedTest
    @MethodSource("retries")
    void retry(String at) {
        var window = GrowerWindow.onNotice(null, GrowerNotice.reply(), ist("2026-11-19T22:30:00"), IST).next();
        var retried = window.retryAt(ist(at));
        assertThat(retried.dueAt()).isEqualTo(ist(at));
        assertThat(retried.pendingCount()).isEqualTo(window.pendingCount());
        assertThat(retried.batchSeq()).isEqualTo(window.batchSeq());
    }
}
