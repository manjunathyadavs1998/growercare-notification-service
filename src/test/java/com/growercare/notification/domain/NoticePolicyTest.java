package com.growercare.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SPEC-NOT-02 D9 / R51 (NOT-E50): the batching window and quiet hours as environment settings. */
class NoticePolicyTest {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    static Instant ist(String local) {
        return LocalDateTime.parse(local).atZone(IST).toInstant();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(textBlock = """
            # example,                                                window, quiet hours, created at,          reply at,            reply sent at
            NOT-E50 local stack: 20 s window and no quiet hours at 22:30, PT20S, false,       2026-11-19T22:30:00, 2026-11-19T22:30:05, 2026-11-19T22:30:20
            NOT-E50 local stack: after the 20 s window the reply goes,  PT20S, false,       2026-11-19T22:30:00, 2026-11-19T22:30:20, 2026-11-19T22:30:20
            NOT-E50 defaults: 5 minutes then quiet hours until 07:00,   PT5M,  true,        2026-11-19T20:58:00, 2026-11-19T20:59:00, 2026-11-20T07:00:00
            """)
    void replyAfterCreation(String example, Duration window, boolean quietHours, String createdAt, String replyAt, String sentAt) {
        var policy = new NoticePolicy(window, quietHours);
        var created = GrowerWindow.onNotice(null, GrowerNotice.created(), ist(createdAt), IST, policy);
        assertThat(created.sendNow()).as(example).isTrue();
        var reply = GrowerWindow.onNotice(created.next(), GrowerNotice.reply(), ist(replyAt), IST, policy);
        var expected = ist(sentAt);
        if (expected.equals(ist(replyAt))) {
            assertThat(reply.sendNow()).as(example).isTrue();
        } else {
            assertThat(reply.sendNow()).as(example).isFalse();
            assertThat(reply.next().dueAt()).as(example).isEqualTo(expected);
            assertThat(reply.next().released(expected, policy).windowEndsAt()).isEqualTo(expected.plus(window));
        }
    }

    @ParameterizedTest(name = "NOT-E50 window {0} is refused")
    @CsvSource({"PT0S", "-PT1M", "PT1H1S"})
    void invalidWindowsAreRefused(Duration window) {
        assertThatThrownBy(() -> new NoticePolicy(window, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "NOT-R51 the spec defaults: {0}")
    @CsvSource("PT5M")
    void defaults(Duration window) {
        assertThat(NoticePolicy.DEFAULT).isEqualTo(new NoticePolicy(window, true));
        assertThat(GrowerWindow.LENGTH).isEqualTo(window);
    }
}
