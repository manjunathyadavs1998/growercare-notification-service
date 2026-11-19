package com.growercare.notification.adapters.in.messaging;

import com.growercare.notification.application.CaseNotifier;
import com.growercare.notification.domain.CaseEvent;
import com.growercare.outbox.inbox.EventEnvelope;
import com.growercare.outbox.inbox.InboxDeduplicator;
import java.util.LinkedHashMap;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Inbound adapter for {@code case.case.events} (SPEC-NOT-02 D1). The inbox row, the window update and the message share
 * one transaction (R47); retries and the DLQ are configured in {@code CaseEventsConfig}. The contact lookup runs
 * inside that transaction on purpose, so a duplicate delivery never sends twice.
 */
@Component
public class CaseEventsListener {

    public static final String GROUP = "notification-service.case-notices";

    private final CaseNotifier notifier;
    private final InboxDeduplicator inbox;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public CaseEventsListener(CaseNotifier notifier, InboxDeduplicator inbox, TransactionTemplate tx,
                              JsonMapper json) {
        this.notifier = notifier;
        this.inbox = inbox;
        this.tx = tx;
        this.json = json;
    }

    @KafkaListener(id = "case-notices", groupId = GROUP, topics = "${gc.events.case-topic:case.case.events}",
            autoStartup = "${gc.events.consumers-enabled:true}")
    public void onCaseEvent(ConsumerRecord<String, String> record) {
        var envelope = EventEnvelope.parse(json, record.value());           // malformed → DLQ without retry
        var data = new LinkedHashMap<String, String>();
        envelope.data().properties().forEach(field -> {
            if (field.getValue().isValueNode() && !field.getValue().isNull()) {
                data.put(field.getKey(), field.getValue().asString());
            }
        });
        var event = new CaseEvent(envelope.eventId(), envelope.eventType(), data);
        tx.executeWithoutResult(status -> {
            if (inbox.firstDelivery(GROUP, event.eventId())) {
                notifier.handle(event);
            }
        });
    }
}
