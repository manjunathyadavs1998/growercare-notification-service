package com.growercare.notification.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.growercare.common.error.DependencyUnavailableException;
import com.growercare.notification.adapters.in.messaging.CaseEventsListener;
import com.growercare.notification.application.CaseNotifier;
import com.growercare.notification.application.port.ContactDirectory;
import com.growercare.notification.config.CaseEventsConfig;
import com.growercare.notification.support.NotificationApiTestBase;
import com.growercare.testsupport.KafkaTestContainer;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * SPEC-NOT-02 R40–R48, R50 / E40–E47, E49: case events → messages in the dev mailbox. Real Kafka and Postgres; the
 * contact directory is a fake (grower-service and IAM are covered by {@code HttpContactDirectoryTest}); the clock is
 * fake and the release job is driven by the test. Every row uses its own case, grower and phone.
 */
@Import(CaseNotificationsIT.Fakes.class)
class CaseNotificationsIT extends NotificationApiTestBase {

    static final String TOPIC = "case.case.events";
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    static final AtomicLong IDS = new AtomicLong(9_000_000_001L);

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry r) {
        r.add("spring.kafka.bootstrap-servers", KafkaTestContainer::bootstrapServers);
        r.add("gc.events.consumers-enabled", () -> "true");
        r.add("gc.events.retry-initial-backoff", () -> "50ms");
    }

    /** A clock the test moves. */
    static final class MutableClock extends Clock {
        volatile Instant now = Instant.parse("2026-11-19T04:30:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Contacts by id; ids in {@code failing} answer like grower-service being down. */
    static final class FakeContacts implements ContactDirectory {
        final Map<Long, GrowerContact> growers = new ConcurrentHashMap<>();
        final Map<Long, StaffContact> staff = new ConcurrentHashMap<>();
        final Set<Long> failing = ConcurrentHashMap.newKeySet();

        @Override
        public Optional<GrowerContact> grower(long growerId) {
            if (failing.contains(growerId)) {
                throw new DependencyUnavailableException("grower-service", null);
            }
            return Optional.ofNullable(growers.get(growerId));
        }

        @Override
        public Optional<StaffContact> staff(long userId) {
            return Optional.ofNullable(staff.get(userId));
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        FakeContacts fakeContacts() {
            return new FakeContacts();
        }
    }

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    MutableClock clock;
    @Autowired
    FakeContacts contacts;
    @Autowired
    CaseNotifier notifier;
    @Autowired
    MeterRegistry meters;

    record Fixture(long caseId, long growerId, String phone) { }

    record Mail(String template, String language, String body) { }

    /** A new case of a grower with a phone in {@code language} (no event sent yet). */
    Fixture grower(String language) {
        var fixture = new Fixture(IDS.getAndIncrement(), IDS.getAndIncrement(), freshPhone());
        contacts.growers.put(fixture.growerId(), new ContactDirectory.GrowerContact(fixture.phone(), language));
        return fixture;
    }

    void at(String istLocal) {
        clock.now = LocalDateTime.parse(istLocal).atZone(IST).toInstant();
    }

    UUID publish(long caseId, String type, Map<String, String> data) throws Exception {
        return publish(UUID.randomUUID(), caseId, type, data, true);
    }

    UUID publish(UUID eventId, long caseId, String type, Map<String, String> data, boolean awaitInbox) throws Exception {
        var payload = new LinkedHashMap<String, Object>(data);
        payload.put("caseId", Long.toString(caseId));
        var envelope = Map.of("eventId", eventId.toString(), "eventType", type, "occurredAt", clock.now.toString(), "data", payload);
        kafka.send(TOPIC, Long.toString(caseId), JSON.writeValueAsString(envelope)).get();
        if (awaitInbox) {
            await().atMost(Duration.ofSeconds(30)).until(() -> inboxRows(eventId) == 1);
        }
        return eventId;
    }

    UUID created(Fixture f) throws Exception {
        return publish(f.caseId(), "case.created", Map.of("growerId", Long.toString(f.growerId()), "countryCode", "IN",
                "caseType", "PRODUCT_COMPLAINT", "status", "SUBMITTED"));
    }

    int inboxRows(UUID eventId) {
        return owner.sql("SELECT count(*) FROM notify.inbox WHERE consumer_group = ? AND event_id = ?")
                .params(CaseEventsListener.GROUP, eventId).query(Integer.class).single();
    }

    List<Mail> mailbox(String recipient) {
        return owner.sql("SELECT template, language, body FROM notify.dev_mailbox WHERE recipient = ? ORDER BY created_at, message_id")
                .param(recipient).query((rs, n) -> new Mail(rs.getString(1), rs.getString(2), rs.getString(3))).list();
    }

    List<String> templates(String recipient) {
        return mailbox(recipient).stream().map(Mail::template).toList();
    }

    double skipped(String reason) {
        return meters.counter(CaseNotifier.SKIPPED, "reason", reason).count();
    }

    // ------------------------------------------------------------------------------------------------ R40

    @Test
    void NOT_E40_theGrowerHearsOfTheNewCaseInTheirLanguage() throws Exception {
        var g1 = grower("mr");
        at("2026-11-19T10:00:00");
        created(g1);
        var mail = mailbox(g1.phone());
        assertThat(mail).hasSize(1);
        assertThat(mail.getFirst().template()).isEqualTo("case.created");
        assertThat(mail.getFirst().language()).isEqualTo("mr");
        assertThat(mail.getFirst().body()).contains("तुमची केस मिळाली").contains("http://grower-app.test/cases/" + g1.caseId());
        assertThat(owner.sql("SELECT grower_id FROM notify.case_ref WHERE case_id = ?").param(g1.caseId()).query(Long.class).single())
                .isEqualTo(g1.growerId());
    }

    // ------------------------------------------------------------------------------------------------ R41–R43

    static Stream<Arguments> followUps() {
        return Stream.of(
                argumentSet("NOT-E41 care.in public reply → case.reply", "mr", "comment.posted",
                        Map.of("visibility", "PUBLIC", "authorRole", "CARE_AGENT", "commentId", "1"), "case.reply", "नवीन उत्तर"),
                argumentSet("NOT-E41 care.in internal note → nothing", "mr", "comment.posted",
                        Map.of("visibility", "INTERNAL", "authorRole", "CARE_AGENT", "commentId", "2"), null, null),
                argumentSet("NOT-E41 the grower's own comment → nothing", "mr", "comment.posted",
                        Map.of("visibility", "PUBLIC", "authorRole", "GROWER", "commentId", "3"), null, null),
                argumentSet("NOT-E42 SUBMITTED → TRIAGED → case.status 'under review'", "en", "case.status_changed",
                        Map.of("from", "SUBMITTED", "to", "TRIAGED", "version", "2"), "case.status", "under review"),
                argumentSet("NOT-E42 SUBMITTED → TRIAGED in Marathi", "mr", "case.status_changed",
                        Map.of("from", "SUBMITTED", "to", "TRIAGED", "version", "2"), "case.status", "पुनरावलोकनात"),
                argumentSet("NOT-E42 TRIAGED → CLASSIFIED → nothing", "mr", "case.status_changed",
                        Map.of("from", "TRIAGED", "to", "CLASSIFIED", "version", "3"), null, null),
                argumentSet("NOT-E42 → CLOSED → case.closed", "pt", "case.status_changed",
                        Map.of("from", "ADVICE_GIVEN", "to", "CLOSED", "version", "4"), "case.closed", "foi encerrado"));
    }

    /** Created at 10:00 (sent at once); the event under test at 10:10, after the window. */
    @ParameterizedTest
    @MethodSource("followUps")
    void followUpEvents(String language, String type, Map<String, String> data, String expectedTemplate, String bodyFragment) throws Exception {
        var g = grower(language);
        at("2026-11-19T10:00:00");
        created(g);
        at("2026-11-19T10:10:00");
        publish(g.caseId(), type, data);
        var mail = mailbox(g.phone());
        if (expectedTemplate == null) {
            assertThat(templates(g.phone())).containsExactly("case.created");
        } else {
            assertThat(templates(g.phone())).containsExactly("case.created", expectedTemplate);
            assertThat(mail.getLast().body()).contains(bodyFragment).contains("/cases/" + g.caseId());
        }
    }

    // ------------------------------------------------------------------------------------------------ R44

    @Test
    void NOT_E43_theAssigneeIsEmailedAtOnceEvenAtNight() throws Exception {
        long staffId = IDS.getAndIncrement();
        var email = "agro.in." + staffId + "@growercare.test";
        contacts.staff.put(staffId, new ContactDirectory.StaffContact(email, "en"));
        long caseId = IDS.getAndIncrement();
        at("2026-11-19T23:30:00");                                            // quiet hours do not apply to staff (D6)
        publish(caseId, "case.assigned", Map.of("assigneeUserId", Long.toString(staffId), "version", "2"));
        var mail = mailbox(email);
        assertThat(mail).extracting(Mail::template).containsExactly("staff.assigned");
        assertThat(mail.getFirst().body()).contains("http://staff-app.test/cases/" + caseId);
    }

    // ------------------------------------------------------------------------------------------------ R45

    @Test
    void NOT_E44_eventsWithinFiveMinutesAreMergedIntoOneMessage() throws Exception {
        var g = grower("en");
        at("2026-11-19T10:00:00");
        created(g);
        at("2026-11-19T10:01:00");
        publish(g.caseId(), "comment.posted", Map.of("visibility", "PUBLIC", "authorRole", "CARE_AGENT", "commentId", "11"));
        at("2026-11-19T10:03:00");
        publish(g.caseId(), "case.status_changed", Map.of("from", "SUBMITTED", "to", "TRIAGED", "version", "2"));
        assertThat(templates(g.phone())).containsExactly("case.created");

        at("2026-11-19T10:04:59");
        notifier.releaseDue();
        assertThat(templates(g.phone())).containsExactly("case.created");

        at("2026-11-19T10:05:00");
        notifier.releaseDue();
        var mail = mailbox(g.phone());
        assertThat(mail).extracting(Mail::template).containsExactly("case.created", "case.updates");
        assertThat(mail.getLast().body()).contains("2 updates");

        notifier.releaseDue();                                                // released once only
        assertThat(mailbox(g.phone())).hasSize(2);
    }

    // ------------------------------------------------------------------------------------------------ R46

    static Stream<Arguments> quietHours() {
        return Stream.of(
                argumentSet("NOT-E45 reply at 22:30 IST → 07:00 the next day", "2026-11-20T22:30:00", "2026-11-21T07:00:00"),
                argumentSet("NOT-E45 reply at 06:59 → 07:00", "2026-11-20T06:59:00", "2026-11-20T07:00:00"),
                argumentSet("NOT-E45 reply at 07:00 → at once", "2026-11-20T07:00:00", null),
                argumentSet("NOT-E45 reply at 20:59 → at once", "2026-11-20T20:59:00", null),
                argumentSet("NOT-E45 reply at 21:00 → 07:00 the next day", "2026-11-20T21:00:00", "2026-11-21T07:00:00"));
    }

    @ParameterizedTest
    @MethodSource("quietHours")
    void growerMessagesWaitForTheEndOfQuietHours(String replyAt, String releasedAt) throws Exception {
        var g = grower("hi");
        at("2026-11-19T12:00:00");
        created(g);
        at(replyAt);
        publish(g.caseId(), "comment.posted", Map.of("visibility", "PUBLIC", "authorRole", "CARE_AGENT", "commentId", "21"));
        if (releasedAt == null) {
            assertThat(templates(g.phone())).containsExactly("case.created", "case.reply");
            return;
        }
        assertThat(templates(g.phone())).containsExactly("case.created");
        clock.now = LocalDateTime.parse(releasedAt).atZone(IST).toInstant().minusSeconds(1);
        notifier.releaseDue();
        assertThat(templates(g.phone())).containsExactly("case.created");
        at(releasedAt);
        notifier.releaseDue();
        assertThat(templates(g.phone())).containsExactly("case.created", "case.reply");
        assertThat(mailbox(g.phone()).getLast().body()).contains("नया जवाब");
    }

    // ------------------------------------------------------------------------------------------------ R47

    @Test
    void NOT_E46_aRedeliveredEventSendsOneMessage() throws Exception {
        var g = grower("mr");
        at("2026-11-19T10:00:00");
        created(g);
        at("2026-11-19T10:10:00");
        var reply = UUID.randomUUID();
        var data = Map.of("visibility", "PUBLIC", "authorRole", "CARE_AGENT", "commentId", "31");
        publish(reply, g.caseId(), "comment.posted", data, false);
        publish(reply, g.caseId(), "comment.posted", data, false);
        publish(g.caseId(), "case.routed", Map.of("queueCode", "IN_CARE"));    // same key: processed after both deliveries
        assertThat(inboxRows(reply)).isEqualTo(1);
        assertThat(templates(g.phone())).containsExactly("case.created", "case.reply");
    }

    // ------------------------------------------------------------------------------------------------ R48, R50

    @Test
    void NOT_E47_aGrowerWithoutContactIsSkippedAndCountedNotRetried() throws Exception {
        var g = new Fixture(IDS.getAndIncrement(), IDS.getAndIncrement(), freshPhone());     // not in the directory: 404
        var before = skipped("contact-missing");
        at("2026-11-19T10:00:00");
        created(g);
        assertThat(mailbox(g.phone())).isEmpty();
        assertThat(skipped("contact-missing")).isEqualTo(before + 1);
    }

    @Test
    void NOT_E47_whenGrowerServiceIsDownTheEventEndsOnTheDlq() throws Exception {
        var g = grower("mr");
        contacts.failing.add(g.growerId());
        at("2026-11-19T10:00:00");
        var eventId = publish(UUID.randomUUID(), g.caseId(), "case.created",
                Map.of("growerId", Long.toString(g.growerId()), "countryCode", "IN"), false);
        var dead = awaitDlq(Long.toString(g.caseId()));
        assertThat(dead).contains(eventId.toString());
        assertThat(inboxRows(eventId)).isZero();
        assertThat(mailbox(g.phone())).isEmpty();
        assertThat(owner.sql("SELECT count(*) FROM notify.case_ref WHERE case_id = ?").param(g.caseId()).query(Integer.class).single())
                .isZero();
    }

    @Test
    void NOT_E49_anEventForAnUnknownCaseIsSkippedAndCounted() throws Exception {
        var g = grower("mr");
        var before = skipped("case-unknown");
        at("2026-11-19T10:00:00");
        publish(g.caseId(), "comment.posted", Map.of("visibility", "PUBLIC", "authorRole", "CARE_AGENT", "commentId", "41"));
        assertThat(mailbox(g.phone())).isEmpty();
        assertThat(skipped("case-unknown")).isEqualTo(before + 1);
    }

    String awaitDlq(String key) {
        var props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainer.bootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlq-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(CaseEventsConfig.DLQ_TOPIC));
            var found = new ArrayList<String>();
            await().atMost(Duration.ofSeconds(60)).until(() -> {
                consumer.poll(Duration.ofMillis(500)).forEach(record -> {
                    if (key.equals(record.key())) {
                        found.add(record.value());
                    }
                });
                return !found.isEmpty();
            });
            return found.getFirst();
        }
    }
}
