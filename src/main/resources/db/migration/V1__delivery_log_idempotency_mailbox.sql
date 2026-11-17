-- =====================================================================================================
-- notification-service · V1 · delivery log, idempotency, dev mailbox (SPEC-NOT-01 D3, D4, D6, D8)
-- Not sharded in Phase 1 (OTPs only); monthly partitions arrive with event notifications (SPEC-NOT-02).
-- Placeholders: ${app_role}
-- =====================================================================================================
GRANT USAGE ON SCHEMA notify TO ${app_role};

-- D4: what was sent, never the body or the full recipient.
CREATE TABLE notify.delivery_log (
    message_id        uuid         PRIMARY KEY,
    caller            varchar(64)  NOT NULL,                                       -- Keycloak client id
    channel           varchar(16)  NOT NULL CHECK (channel IN ('SMS', 'WHATSAPP', 'EMAIL')),
    template          varchar(64)  NOT NULL,
    language          varchar(8)   NOT NULL,
    category          varchar(16)  NOT NULL CHECK (category IN ('OTP', 'TRANSACTIONAL', 'MARKETING')),
    recipient_masked  varchar(64)  NOT NULL,
    status            varchar(16)  NOT NULL CHECK (status IN ('DISPATCHED', 'FAILED')),
    created_at        timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX delivery_log_created_ix ON notify.delivery_log (created_at);

-- D6: a retried request returns the original message; another request with the same key is a conflict.
CREATE TABLE notify.message_idempotency (
    idempotency_key   uuid         PRIMARY KEY,
    caller            varchar(64)  NOT NULL,
    request_hash      bytea        NOT NULL CHECK (octet_length(request_hash) = 32),
    message_id        uuid         NOT NULL REFERENCES notify.delivery_log (message_id),
    created_at        timestamptz  NOT NULL DEFAULT now()
);

-- D3: LOCAL ONLY. Written by the mailbox provider adapter, which cannot run together with a real provider.
CREATE TABLE notify.dev_mailbox (
    message_id        uuid         PRIMARY KEY REFERENCES notify.delivery_log (message_id),
    channel           varchar(16)  NOT NULL,
    recipient         varchar(254) NOT NULL,
    template          varchar(64)  NOT NULL,
    language          varchar(8)   NOT NULL,
    body              text         NOT NULL,
    created_at        timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX dev_mailbox_recipient_ix ON notify.dev_mailbox (recipient, created_at DESC);

-- Runtime role: append-only (the databases grant full DML by default, so the restriction is an explicit REVOKE).
GRANT SELECT, INSERT ON notify.delivery_log, notify.message_idempotency, notify.dev_mailbox TO ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON notify.delivery_log, notify.message_idempotency, notify.dev_mailbox FROM ${app_role};
