package com.growercare.notification.domain;

import java.util.Map;

/** SPEC-NOT-02 D2, R40–R44: who is told what about a case event. */
public final class CaseNoticeRules {

    /** The author role of a grower's own comment (IAM role GROWER). */
    static final String GROWER_ROLE = "GROWER";

    public sealed interface Decision permits Nothing, ToGrower, ToAssignee { }

    /** Nobody is told (an internal note, the grower's own comment, a staff-internal status, any other event). */
    public record Nothing() implements Decision { }

    /** The grower of the case (SMS), subject to batching and quiet hours. */
    public record ToGrower(GrowerNotice notice) implements Decision { }

    /** The new assignee (email), at once. */
    public record ToAssignee(long userId) implements Decision { }

    static final Nothing NOTHING = new Nothing();

    private CaseNoticeRules() {
    }

    /**
     * @param visibleStatuses customer-visible status codes (D3) → whether entering it closes the case
     */
    public static Decision decide(CaseEvent event, Map<String, Boolean> visibleStatuses) {
        return switch (event.type()) {
            case "case.created" -> new ToGrower(GrowerNotice.created());                                         // R40
            case "comment.posted" -> "PUBLIC".equals(event.text("visibility")) && !GROWER_ROLE.equals(event.text("authorRole"))
                    ? new ToGrower(GrowerNotice.reply())                                                         // R41
                    : NOTHING;                                                                                    // R42
            case "case.status_changed" -> {                                                                       // R43
                var to = event.requiredText("to");
                var closes = visibleStatuses.get(to);
                yield closes == null ? NOTHING : new ToGrower(closes ? GrowerNotice.closed() : GrowerNotice.status(to));
            }
            case "case.assigned" -> new ToAssignee(event.id("assigneeUserId"));                                   // R44
            default -> NOTHING;
        };
    }
}
