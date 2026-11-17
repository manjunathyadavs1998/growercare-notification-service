package com.growercare.notification.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.notification.support.NotificationApiTestBase;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.TestPropertySource;

/**
 * SPEC-NOT-01 R9 (NOT-E9): without {@code GC_DEV_MAILBOX} the mailbox routes do not exist. In Phase 1 there is no real
 * provider either, so a send fails closed (503) instead of silently disappearing.
 */
@TestPropertySource(properties = {
        "gc.notification.dev-mailbox=false", "gc.notification.sms-provider=", "gc.notification.whatsapp-provider=",
        "gc.notification.email-provider="})
class DevMailboxDisabledIT extends NotificationApiTestBase {

    @ParameterizedTest(name = "NOT-E9 {0} does not exist")
    @ValueSource(strings = {"/dev/mailbox", "/dev/mailbox/ui"})
    void mailboxRoutesAreAbsent(String path) throws Exception {
        // the routes are not registered: an unauthenticated request is refused before routing, an authenticated one is 404
        mvc.perform(get(path).param("recipient", "+919100000000").header("Authorization", GROWER_SERVICE))
                .andExpect(status().isNotFound());
    }

    @Test
    void NOT_E9_withoutAProviderASendFailsClosed() throws Exception {
        problem(send(GROWER_SERVICE, otp(freshPhone(), "123123"), UUID.randomUUID()), 503, "DEPENDENCY_UNAVAILABLE");
    }
}
