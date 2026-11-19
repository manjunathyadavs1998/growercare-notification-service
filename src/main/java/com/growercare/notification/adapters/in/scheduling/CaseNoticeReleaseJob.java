package com.growercare.notification.adapters.in.scheduling;

import com.growercare.notification.application.CaseNotifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** SPEC-NOT-02 D5/D6: releases due grower windows ({@code GC_CASE_NOTICE_RELEASE_INTERVAL_MS}, default 15 s). */
@Component
@ConditionalOnProperty(name = "gc.case-notices.release-enabled", havingValue = "true", matchIfMissing = true)
class CaseNoticeReleaseJob {

    private final CaseNotifier notifier;

    CaseNoticeReleaseJob(CaseNotifier notifier) {
        this.notifier = notifier;
    }

    @Scheduled(fixedDelayString = "${gc.case-notices.release-interval-ms:15000}", initialDelayString = "${gc.case-notices.release-interval-ms:15000}")
    void release() {
        notifier.releaseDue();
    }
}
