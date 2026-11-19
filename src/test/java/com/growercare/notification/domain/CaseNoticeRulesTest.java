package com.growercare.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.notification.domain.CaseNoticeRules.Decision;
import com.growercare.notification.domain.CaseNoticeRules.Nothing;
import com.growercare.notification.domain.CaseNoticeRules.ToAssignee;
import com.growercare.notification.domain.CaseNoticeRules.ToGrower;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** SPEC-NOT-02 D2/D3, R40–R44: who is told what (rows = spec examples). */
class CaseNoticeRulesTest {

    static final Map<String, Boolean> VISIBLE = Map.of("TRIAGED", false, "NEED_INFO", false, "CLOSED", true);

    static CaseEvent event(String type, String... keyValues) {
        var data = new java.util.HashMap<String, String>();
        data.put("caseId", "7001");
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            data.put(keyValues[i], keyValues[i + 1]);
        }
        return new CaseEvent(UUID.randomUUID(), type, data);
    }

    static Stream<Arguments> decisions() {
        return Stream.of(
                argumentSet("NOT-E40 case.created → the grower", event("case.created", "growerId", "1", "countryCode", "IN"),
                        new ToGrower(GrowerNotice.created())),
                argumentSet("NOT-E41 care.in public reply → the grower", event("comment.posted", "visibility", "PUBLIC", "authorRole", "CARE_AGENT"),
                        new ToGrower(GrowerNotice.reply())),
                argumentSet("NOT-E41 internal note → nobody", event("comment.posted", "visibility", "INTERNAL", "authorRole", "CARE_AGENT"),
                        CaseNoticeRules.NOTHING),
                argumentSet("NOT-E41 the grower's own comment → nobody", event("comment.posted", "visibility", "PUBLIC", "authorRole", "GROWER"),
                        CaseNoticeRules.NOTHING),
                argumentSet("NOT-E41 a partner-only comment → nobody", event("comment.posted", "visibility", "PARTNER", "authorRole", "RETAILER_USER"),
                        CaseNoticeRules.NOTHING),
                argumentSet("NOT-E42 SUBMITTED → TRIAGED: case.status", event("case.status_changed", "from", "SUBMITTED", "to", "TRIAGED"),
                        new ToGrower(GrowerNotice.status("TRIAGED"))),
                argumentSet("NOT-E42 TRIAGED → CLASSIFIED: nobody", event("case.status_changed", "from", "TRIAGED", "to", "CLASSIFIED"),
                        CaseNoticeRules.NOTHING),
                argumentSet("NOT-E42 → CLOSED: case.closed", event("case.status_changed", "from", "ADVICE_GIVEN", "to", "CLOSED"),
                        new ToGrower(GrowerNotice.closed())),
                argumentSet("NOT-E43 assigned to agro.in → the assignee", event("case.assigned", "assigneeUserId", "102"),
                        new ToAssignee(102)),
                argumentSet("NOT-R40 other events (case.routed) → nobody", event("case.routed", "queueCode", "IN_CARE"),
                        CaseNoticeRules.NOTHING));
    }

    @ParameterizedTest
    @MethodSource("decisions")
    void decide(CaseEvent event, Decision expected) {
        assertThat(CaseNoticeRules.decide(event, VISIBLE)).isEqualTo(expected);
    }

    static Stream<Arguments> malformed() {
        return Stream.of(
                argumentSet("NOT-R48 status change without 'to'", event("case.status_changed", "from", "SUBMITTED")),
                argumentSet("NOT-R48 assignment without an assignee", event("case.assigned")),
                argumentSet("NOT-R48 assignment with a malformed assignee", event("case.assigned", "assigneeUserId", "abc")));
    }

    /** Malformed events are never retried (CaseEvent.Malformed → DLQ at once). */
    @ParameterizedTest
    @MethodSource("malformed")
    void malformedEventsAreRejected(CaseEvent event) {
        assertThatThrownBy(() -> CaseNoticeRules.decide(event, VISIBLE)).isInstanceOf(CaseEvent.Malformed.class);
    }

    static Stream<Arguments> caseIds() {
        return Stream.of(
                argumentSet("NOT-R48 caseId missing", Map.of()),
                argumentSet("NOT-R48 caseId zero", Map.of("caseId", "0")),
                argumentSet("NOT-R48 caseId beyond a long", Map.of("caseId", "9999999999999999999")));
    }

    @ParameterizedTest
    @MethodSource("caseIds")
    void caseIdsAreValidated(Map<String, String> data) {
        var event = new CaseEvent(UUID.randomUUID(), "case.created", data);
        assertThatThrownBy(event::caseId).isInstanceOf(CaseEvent.Malformed.class);
        assertThat(new Nothing()).isEqualTo(CaseNoticeRules.NOTHING);
    }
}
