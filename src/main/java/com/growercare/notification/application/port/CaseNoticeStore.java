package com.growercare.notification.application.port;

import com.growercare.notification.domain.GrowerWindow;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** SPEC-NOT-02 §4: case references, grower windows and reference data (schema {@code notify}). */
public interface CaseNoticeStore {

    /** The only case data kept (D4): whose case it is and in which country. */
    record CaseRef(long caseId, long growerId, String countryCode) { }

    /** Idempotent: a second {@code case.created} for the same case changes nothing. */
    void saveCaseRef(CaseRef ref);

    Optional<CaseRef> caseRef(long caseId);

    /** D3: customer-visible status code → whether entering it closes the case. */
    Map<String, Boolean> visibleStatuses();

    Optional<String> statusLabel(String statusCode, String language);

    /** D6: the country's time zone (reference data); fails when the country is unknown. */
    ZoneId timeZone(String countryCode);

    /** The case's window, locked until the transaction ends. */
    Optional<GrowerWindow> lockWindow(long caseId);

    void saveWindow(long caseId, GrowerWindow window);

    /** Cases whose waiting notices are due at {@code now}, oldest first. */
    List<Long> dueCases(Instant now, int limit);

    /** The window if it is still due and nobody else holds it (skip locked). */
    Optional<GrowerWindow> lockDueWindow(long caseId, Instant now);
}
