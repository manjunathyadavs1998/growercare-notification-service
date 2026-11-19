package com.growercare.notification.application;

import com.growercare.notification.application.port.CaseNoticeStore;
import com.growercare.notification.application.port.CaseNoticeStore.CaseRef;
import com.growercare.notification.application.port.ContactDirectory;
import com.growercare.notification.domain.CaseEvent;
import com.growercare.notification.domain.CaseNoticeRules;
import com.growercare.notification.domain.GrowerNotice;
import com.growercare.notification.domain.GrowerWindow;
import com.growercare.notification.domain.MessageRequest;
import com.growercare.notification.domain.MessageRequest.Category;
import com.growercare.notification.domain.MessageRequest.Channel;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * SPEC-NOT-02: turns case events into messages. {@link #handle} runs inside the consumer's transaction (after the inbox
 * check), so a failure rolls everything back and the event is retried, then dead-lettered (R47, R48).
 * {@link #releaseDue} sends the notices that waited for the end of a window or of quiet hours (R45, R46).
 */
@Service
public class CaseNotifier {

    public static final String CALLER = "notification-service";
    public static final String SKIPPED = "notification.case.skipped";
    static final String STAFF_ASSIGNED = "staff.assigned";
    static final Duration RETRY_AFTER = Duration.ofMinutes(1);
    static final int RELEASE_BATCH = 100;

    private static final Logger LOG = LoggerFactory.getLogger(CaseNotifier.class);

    /** D7: where the messages link to (the grower app, the staff console). */
    public record Links(String growerApp, String staffApp) {
        public Links {
            growerApp = strip(growerApp);
            staffApp = strip(staffApp);
        }

        String growerCase(long caseId) {
            return growerApp + "/cases/" + caseId;
        }

        String staffCase(long caseId) {
            return staffApp + "/cases/" + caseId;
        }

        private static String strip(String url) {
            return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        }
    }

    private final CaseNoticeStore store;
    private final ContactDirectory contacts;
    private final MessageDispatch dispatch;
    private final Links links;
    private final Clock clock;
    private final MeterRegistry meters;
    private final TransactionTemplate tx;

    public CaseNotifier(CaseNoticeStore store, ContactDirectory contacts, MessageDispatch dispatch, Links links, Clock clock,
                        MeterRegistry meters, TransactionTemplate tx) {
        this.store = store;
        this.contacts = contacts;
        this.dispatch = dispatch;
        this.links = links;
        this.clock = clock;
        this.meters = meters;
        this.tx = tx;
    }

    /** One event, first delivery. Must run inside the transaction that recorded it in the inbox. */
    public void handle(CaseEvent event) {
        var decision = CaseNoticeRules.decide(event, store.visibleStatuses());
        if ("case.created".equals(event.type())) {                                                        // R40, D4
            store.saveCaseRef(new CaseRef(event.caseId(), event.id("growerId"), event.requiredText("countryCode")));
        }
        switch (decision) {
            case CaseNoticeRules.Nothing nothing -> { }
            case CaseNoticeRules.ToAssignee assignee -> notifyAssignee(event, assignee.userId());
            case CaseNoticeRules.ToGrower grower -> notifyGrower(event, grower.notice());
        }
    }

    /** R45/R46: sends every due window; a window whose send fails is retried a minute later (R48). */
    public int releaseDue() {
        var now = clock.instant();
        int released = 0;
        for (long caseId : store.dueCases(now, RELEASE_BATCH)) {
            try {
                Boolean done = tx.execute(status -> store.lockDueWindow(caseId, now).map(window -> {
                    var ref = store.caseRef(caseId).orElseThrow(() -> new IllegalStateException("window without case_ref " + caseId));
                    sendToGrower(ref, window.release(), key("batch:" + caseId + ":" + window.batchSeq()));
                    store.saveWindow(caseId, window.released(now));
                    return true;
                }).orElse(false));
                released += Boolean.TRUE.equals(done) ? 1 : 0;
            } catch (RuntimeException e) {
                LOG.warn("Releasing the notices of case {} failed; retrying in {}", caseId, RETRY_AFTER, e);
                tx.executeWithoutResult(status -> store.lockDueWindow(caseId, now)
                        .ifPresent(window -> store.saveWindow(caseId, window.retryAt(now.plus(RETRY_AFTER)))));
            }
        }
        return released;
    }

    private void notifyGrower(CaseEvent event, GrowerNotice notice) {
        long caseId = event.caseId();
        var ref = store.caseRef(caseId);
        if (ref.isEmpty()) {                                                                               // R50 (D8)
            skipped("case-unknown");
            return;
        }
        var step = GrowerWindow.onNotice(store.lockWindow(caseId).orElse(null), notice, clock.instant(),
                store.timeZone(ref.get().countryCode()));
        if (step.sendNow() && !sendToGrower(ref.get(), notice, key("grower:" + event.eventId()))) {
            return;                                                                                         // R48: no contact, no window
        }
        store.saveWindow(caseId, step.next());
    }

    private void notifyAssignee(CaseEvent event, long userId) {                                            // R44
        var contact = contacts.staff(userId);
        if (contact.isEmpty()) {
            skipped("contact-missing");
            return;
        }
        var params = new HashMap<String, String>();
        params.put("link", links.staffCase(event.caseId()));
        dispatch.send(new MessageRequest(CALLER, Channel.EMAIL, contact.get().email(), STAFF_ASSIGNED, contact.get().language(),
                Category.TRANSACTIONAL, params, key("staff:" + event.eventId())));
    }

    /** @return false when the grower has no contact (skipped and counted, R48) */
    private boolean sendToGrower(CaseRef ref, GrowerNotice notice, UUID key) {
        var contact = contacts.grower(ref.growerId());
        if (contact.isEmpty()) {
            skipped("contact-missing");
            return false;
        }
        var language = contact.get().language();
        var params = new HashMap<String, String>();
        params.put("link", links.growerCase(ref.caseId()));
        if (notice.statusCode() != null) {
            params.put("status", store.statusLabel(notice.statusCode(), language).orElseThrow(() ->
                    new IllegalStateException("status " + notice.statusCode() + " has no " + language + " name")));
        }
        if (GrowerNotice.UPDATES.equals(notice.template())) {
            params.put("count", Integer.toString(notice.count()));
        }
        dispatch.send(new MessageRequest(CALLER, Channel.SMS, contact.get().phone(), notice.template(), language,
                Category.TRANSACTIONAL, params, key));
        return true;
    }

    private void skipped(String reason) {
        meters.counter(SKIPPED, "reason", reason).increment();
    }

    /** A redelivered event or a retried release reuses its key, so the NOT-01 dispatch never sends twice. */
    static UUID key(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
