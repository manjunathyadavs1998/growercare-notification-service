package com.growercare.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.growercare.notification.support.NotificationApiTestBase;
import com.growercare.testsupport.TestJwts;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** SPEC-NOT-01 R1–R8, R11 (NOT-E1…E8, E11): dispatching messages through the mailbox provider. */
class MessageDispatchIT extends NotificationApiTestBase {

    private int mailbox(String recipient) {
        return owner.sql("SELECT count(*) FROM notify.dev_mailbox WHERE recipient = ?").param(recipient).query(Integer.class).single();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void NOT_E1_E11_anOtpIsRenderedStoredInTheMailboxAndLoggedWithoutContent(CapturedOutput output) throws Exception {
        var phone = freshPhone();
        var json = send(GROWER_SERVICE, otp(phone, "482913"), UUID.randomUUID())
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DISPATCHED"))
                .andReturn().getResponse().getContentAsString();
        String messageId = JsonPath.read(json, "$.messageId");

        var body = owner.sql("SELECT body FROM notify.dev_mailbox WHERE message_id = ?::uuid").param(messageId).query(String.class).single();
        assertThat(body).contains("482913", "5").startsWith("GrowerCare: ");
        var log = owner.sql("SELECT caller || '|' || channel || '|' || template || '|' || language || '|' || recipient_masked || '|' || status"
                + " FROM notify.delivery_log WHERE message_id = ?::uuid").param(messageId).query(String.class).single();
        assertThat(log).isEqualTo("grower-service|SMS|otp.activation|mr|******" + phone.substring(phone.length() - 4) + "|DISPATCHED");
        // R11: the code and the full number exist only in the mailbox
        assertThat(json).doesNotContain("482913", phone.substring(3));
        assertThat(output.getAll()).doesNotContain("482913", phone.substring(3));
    }

    static Stream<Arguments> languages() {
        return Stream.of(argumentSet("NOT-E2 en", "en"), argumentSet("NOT-E2 hi", "hi"), argumentSet("NOT-E2 pt", "pt"));
    }

    @ParameterizedTest
    @MethodSource("languages")
    void otherLanguagesRenderTheirText(String language) throws Exception {
        var phone = freshPhone();
        send(GROWER_SERVICE, with(otp(phone, "111222"), "language", language), UUID.randomUUID()).andExpect(status().isAccepted());
        var body = owner.sql("SELECT body FROM notify.dev_mailbox WHERE recipient = ?").param(phone).query(String.class).single();
        assertThat(body).contains("111222").startsWith("GrowerCare: ");
    }

    @Test
    void NOT_E3_aReplayReturnsTheSameMessageAndAnotherBodyIsAConflict() throws Exception {
        var phone = freshPhone();
        var key = UUID.randomUUID();
        var first = send(GROWER_SERVICE, otp(phone, "333444"), key).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        send(GROWER_SERVICE, otp(phone, "333444"), key)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.messageId").value((String) JsonPath.read(first, "$.messageId")));
        assertThat(mailbox(phone)).isEqualTo(1);
        problem(send(GROWER_SERVICE, otp(phone, "999000"), key), 409, "IDEMPOTENCY_KEY_REUSED");
    }

    static Stream<Arguments> refused() {
        return Stream.of(
                argumentSet("NOT-E4 unknown template", Map.of("template", "otp.unknown"), "notification.template-unknown"),
                argumentSet("NOT-E4 language without a text", Map.of("language", "ta"), "notification.language-missing"),
                argumentSet("NOT-E4 category not supported in Phase 1", Map.of("category", "MARKETING"), "notification.category-unsupported"),
                argumentSet("NOT-E4 missing parameter", Map.of("params", Map.of("minutes", "5")), "notification.param-missing"));
    }

    @ParameterizedTest
    @MethodSource("refused")
    void businessRulesAre422(Map<String, Object> change, String rule) throws Exception {
        var body = otp(freshPhone(), "123123");
        body.putAll(change);
        problem(send(GROWER_SERVICE, body, UUID.randomUUID()), 422, "BUSINESS_RULE_VIOLATION").andExpect(jsonPath("$.rule").value(rule));
    }

    static Stream<Arguments> invalid() {
        return Stream.of(
                argumentSet("NOT-E5 channel FAX", Map.of("channel", "FAX"), "channel"),
                argumentSet("NOT-E5 SMS recipient not E.164", Map.of("recipient", "98765"), "recipient"),
                argumentSet("NOT-E5 EMAIL recipient not an email", Map.of("channel", "EMAIL", "recipient", "not-an-email"), "recipient"),
                argumentSet("NOT-E5 template missing", Map.of("template", ""), "template"));
    }

    @ParameterizedTest
    @MethodSource("invalid")
    void invalidInputIs400NamingTheField(Map<String, Object> change, String field) throws Exception {
        var body = otp(freshPhone(), "123123");
        body.putAll(change);
        problem(send(GROWER_SERVICE, body, UUID.randomUUID()), 400, "VALIDATION_FAILED").andExpect(jsonPath("$.errors[*].field", hasItem(field)));
    }

    @Test
    void NOT_E5_theIdempotencyKeyIsRequired() throws Exception {
        problem(send(GROWER_SERVICE, otp(freshPhone(), "123123"), null), 400, "VALIDATION_FAILED");
    }

    @Test
    void NOT_E6_aProviderFailureIs503AndLoggedAsFailed() throws Exception {
        var phone = freshPhone();
        provider.failing.set(true);
        try {
            problem(send(GROWER_SERVICE, otp(phone, "555666"), UUID.randomUUID()), 503, "DEPENDENCY_UNAVAILABLE");
        } finally {
            provider.failing.set(false);
        }
        assertThat(mailbox(phone)).isZero();
        var status = owner.sql("SELECT status FROM notify.delivery_log WHERE recipient_masked = ?")
                .param("******" + phone.substring(phone.length() - 4)).query(String.class).list();
        assertThat(status).containsExactly("FAILED");
    }

    static Stream<Arguments> callers() {
        return Stream.of(
                argumentSet("NOT-E7 a user token (no end users here)", TestJwts.user(TestJwts.CARE_IN).bearer()),
                argumentSet("NOT-E7 a service client that is not allow-listed", TestJwts.service("integration-service").bearer()),
                argumentSet("NOT-E7 no token", (Object) null));
    }

    @ParameterizedTest
    @MethodSource("callers")
    void onlyAllowListedServicesMaySend(String authorization) throws Exception {
        problem(send(authorization, otp(freshPhone(), "123123"), UUID.randomUUID()), 401, "UNAUTHENTICATED");
    }

    @Test
    void NOT_E8_theDevMailboxListsMessagesNewestFirst() throws Exception {
        var phone = freshPhone();
        send(GROWER_SERVICE, otp(phone, "100001"), UUID.randomUUID()).andExpect(status().isAccepted());
        send(GROWER_SERVICE, with(otp(phone, "100002"), "language", "en"), UUID.randomUUID()).andExpect(status().isAccepted());
        mvc.perform(get("/dev/mailbox").param("recipient", phone))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].body", org.hamcrest.Matchers.containsString("100002")))
                .andExpect(jsonPath("$.items[1].body", org.hamcrest.Matchers.containsString("100001")))
                .andExpect(jsonPath("$.items[0].language").value("en"));
        mvc.perform(get("/dev/mailbox/ui").param("recipient", phone))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("100002")));
    }
}
