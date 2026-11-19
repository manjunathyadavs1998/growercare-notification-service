package com.growercare.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SPEC-NOT-02 D6 / R46 (NOT-E45): quiet hours 21:00–07:00 in the country's time zone. */
class QuietHoursTest {

    @ParameterizedTest(name = "{0}")
    @CsvSource(textBlock = """
            # example,                              zone,              local time,          earliest send (local)
            NOT-E45 reply at 22:30 IST,             Asia/Kolkata,      2026-11-19T22:30,    2026-11-20T07:00
            NOT-E45 06:59 waits until 07:00,        Asia/Kolkata,      2026-11-20T06:59,    2026-11-20T07:00
            NOT-E45 07:00 is not quiet,             Asia/Kolkata,      2026-11-20T07:00,    2026-11-20T07:00
            NOT-E45 20:59 is not quiet,             Asia/Kolkata,      2026-11-19T20:59,    2026-11-19T20:59
            NOT-E45 21:00 is quiet,                 Asia/Kolkata,      2026-11-19T21:00,    2026-11-20T07:00
            NOT-E45 just after midnight,            Asia/Kolkata,      2026-11-20T00:05,    2026-11-20T07:00
            NOT-E45 Brazil uses its own zone,       America/Sao_Paulo, 2026-11-19T23:00,    2026-11-20T07:00
            """)
    void earliestSend(String example, String zone, LocalDateTime local, LocalDateTime expected) {
        var z = ZoneId.of(zone);
        assertThat(QuietHours.earliestSend(local.atZone(z).toInstant(), z)).isEqualTo(expected.atZone(z).toInstant());
    }
}
