-- notification-service (schema notify, SPEC-NOT-01 §4). Statement ids: notify.<name>

-- name: log
INSERT INTO notify.delivery_log (message_id, caller, channel, template, language, category, recipient_masked, status)
VALUES (:messageId, :caller, :channel, :template, :language, :category, :recipientMasked, :status)

-- name: findIdempotency
SELECT idempotency_key, caller, request_hash, message_id
  FROM notify.message_idempotency
 WHERE idempotency_key = :key

-- name: insertIdempotency
INSERT INTO notify.message_idempotency (idempotency_key, caller, request_hash, message_id)
VALUES (:key, :caller, :requestHash, :messageId)
ON CONFLICT (idempotency_key) DO NOTHING

-- name: insertMailbox
INSERT INTO notify.dev_mailbox (message_id, channel, recipient, template, language, body)
VALUES (:messageId, :channel, :recipient, :template, :language, :body)

-- name: mailbox
SELECT message_id, channel, recipient, template, language, body, created_at
  FROM notify.dev_mailbox
 WHERE recipient = :recipient
 ORDER BY created_at DESC, message_id DESC
 LIMIT :limit
