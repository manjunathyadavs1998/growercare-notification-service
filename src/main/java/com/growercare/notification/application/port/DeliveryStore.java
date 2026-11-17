package com.growercare.notification.application.port;

import com.growercare.notification.domain.MessageRequest;
import java.util.List;
import java.util.Optional;
import java.time.Instant;
import java.util.UUID;

/** Delivery log, idempotency keys and the dev mailbox (SPEC-NOT-01 §4). */
public interface DeliveryStore {

    enum Status { DISPATCHED, FAILED }

    record IdempotencyRecord(UUID key, String caller, byte[] requestHash, UUID messageId) { }

    record MailboxMessage(UUID messageId, String channel, String recipient, String template, String language, String body,
                          Instant createdAt) { }

    /** D4: metadata only — never the body or the full recipient. */
    void log(UUID messageId, MessageRequest request, Status status);

    Optional<IdempotencyRecord> findIdempotency(UUID key);

    /** @return false when another request already used the key */
    boolean saveIdempotency(UUID key, String caller, byte[] requestHash, UUID messageId);

    /** R8: newest first, at most {@code limit}. */
    List<MailboxMessage> mailbox(String recipient, int limit);
}
