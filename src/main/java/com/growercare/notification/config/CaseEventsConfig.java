package com.growercare.notification.config;

import com.growercare.notification.domain.CaseEvent;
import com.growercare.outbox.inbox.InboxDeduplicator;
import com.growercare.outbox.inbox.MalformedEventException;
import java.time.Duration;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * SPEC-NOT-02 D1, R47/R48 (SPEC-PLAT-04 D5/D7): inbox and consumer error handling. A failed event is retried 3 times
 * with exponential back-off (1 s, 2 s, 4 s by default), then goes to {@value #DLQ_TOPIC} with Spring's standard
 * {@code kafka_dlt-*} headers. Malformed events skip the retries.
 */
@Configuration(proxyBeanMethods = false)
public class CaseEventsConfig {

    public static final String DLQ_TOPIC = "dlq.notification-service.case-notices";
    static final int RETRIES = 3;

    @Bean
    InboxDeduplicator inboxDeduplicator(JdbcClient jdbc) {
        return new InboxDeduplicator(jdbc, "notify.inbox");
    }

    /** Picked up by Boot's default listener container factory. */
    @Bean
    CommonErrorHandler kafkaErrorHandler(KafkaTemplate<Object, Object> template,
                                         @Value("${gc.events.retry-initial-backoff:1s}") Duration initialBackoff) {
        var recoverer = new DeadLetterPublishingRecoverer(template, (record, exception) -> new TopicPartition(DLQ_TOPIC, -1));
        var backOff = new ExponentialBackOff(initialBackoff.toMillis(), 2.0);
        backOff.setMaxAttempts(RETRIES);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(MalformedEventException.class, CaseEvent.Malformed.class);
        return handler;
    }
}
