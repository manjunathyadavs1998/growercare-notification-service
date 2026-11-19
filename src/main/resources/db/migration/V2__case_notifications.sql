-- =====================================================================================================
-- notification-service · V2 · case notifications (SPEC-NOT-02 §4)
-- The only case data kept is case_ref (D4); contacts are fetched at send time and never stored.
-- Placeholders: ${app_role}
-- =====================================================================================================

-- D6: quiet hours are in the country's time zone.
CREATE TABLE notify.country_time_zone (
    country_code  char(2)      PRIMARY KEY,
    time_zone     varchar(64)  NOT NULL
);
INSERT INTO notify.country_time_zone (country_code, time_zone) VALUES
    ('IN', 'Asia/Kolkata'),
    ('BR', 'America/Sao_Paulo');

-- D3: statuses the grower hears about (closes = entering it closes the case), and their names per language.
CREATE TABLE notify.visible_status (
    status_code   varchar(40)  PRIMARY KEY,
    closes        boolean      NOT NULL DEFAULT false
);
INSERT INTO notify.visible_status (status_code, closes) VALUES
    ('TRIAGED', false),
    ('NEED_INFO', false),
    ('FIELD_INVESTIGATION', false),
    ('ROOT_CAUSE_DETERMINED', false),
    ('COMPENSATION_APPROVED', false),
    ('NO_COMPENSATION', false),
    ('ADVICE_GIVEN', false),
    ('RESOLVED_BY_ADVICE', false),
    ('REOPENED', false),
    ('CLOSED', true);

CREATE TABLE notify.status_label (
    status_code   varchar(40)  NOT NULL REFERENCES notify.visible_status (status_code),
    language      varchar(8)   NOT NULL,
    label         varchar(80)  NOT NULL,
    PRIMARY KEY (status_code, language)
);
INSERT INTO notify.status_label (status_code, language, label) VALUES
    ('TRIAGED', 'en', 'under review'),
    ('TRIAGED', 'hi', 'समीक्षा में'),
    ('TRIAGED', 'mr', 'पुनरावलोकनात'),
    ('TRIAGED', 'pt', 'em análise'),
    ('NEED_INFO', 'en', 'waiting for more information from you'),
    ('NEED_INFO', 'hi', 'आपसे और जानकारी की प्रतीक्षा'),
    ('NEED_INFO', 'mr', 'तुमच्याकडून अधिक माहितीची प्रतीक्षा'),
    ('NEED_INFO', 'pt', 'aguardando mais informações suas'),
    ('FIELD_INVESTIGATION', 'en', 'field visit planned'),
    ('FIELD_INVESTIGATION', 'hi', 'खेत निरीक्षण की योजना'),
    ('FIELD_INVESTIGATION', 'mr', 'शेत पाहणीचे नियोजन'),
    ('FIELD_INVESTIGATION', 'pt', 'visita de campo planejada'),
    ('ROOT_CAUSE_DETERMINED', 'en', 'cause found'),
    ('ROOT_CAUSE_DETERMINED', 'hi', 'कारण पता चला'),
    ('ROOT_CAUSE_DETERMINED', 'mr', 'कारण सापडले'),
    ('ROOT_CAUSE_DETERMINED', 'pt', 'causa identificada'),
    ('COMPENSATION_APPROVED', 'en', 'compensation approved'),
    ('COMPENSATION_APPROVED', 'hi', 'मुआवज़ा स्वीकृत'),
    ('COMPENSATION_APPROVED', 'mr', 'नुकसानभरपाई मंजूर'),
    ('COMPENSATION_APPROVED', 'pt', 'compensação aprovada'),
    ('NO_COMPENSATION', 'en', 'decided: no compensation'),
    ('NO_COMPENSATION', 'hi', 'निर्णय: कोई मुआवज़ा नहीं'),
    ('NO_COMPENSATION', 'mr', 'निर्णय: नुकसानभरपाई नाही'),
    ('NO_COMPENSATION', 'pt', 'decisão: sem compensação'),
    ('ADVICE_GIVEN', 'en', 'advice given'),
    ('ADVICE_GIVEN', 'hi', 'सलाह दी गई'),
    ('ADVICE_GIVEN', 'mr', 'सल्ला दिला'),
    ('ADVICE_GIVEN', 'pt', 'orientação enviada'),
    ('RESOLVED_BY_ADVICE', 'en', 'resolved with advice'),
    ('RESOLVED_BY_ADVICE', 'hi', 'सलाह से हल'),
    ('RESOLVED_BY_ADVICE', 'mr', 'सल्ल्याने सोडवले'),
    ('RESOLVED_BY_ADVICE', 'pt', 'resolvido com orientação'),
    ('REOPENED', 'en', 'reopened'),
    ('REOPENED', 'hi', 'फिर से खोला गया'),
    ('REOPENED', 'mr', 'पुन्हा उघडले'),
    ('REOPENED', 'pt', 'reaberto'),
    ('CLOSED', 'en', 'closed'),
    ('CLOSED', 'hi', 'बंद'),
    ('CLOSED', 'mr', 'बंद'),
    ('CLOSED', 'pt', 'encerrado');

-- D4/R40: whose case it is, from case.created.
CREATE TABLE notify.case_ref (
    case_id       bigint       PRIMARY KEY,
    grower_id     bigint       NOT NULL,
    country_code  char(2)      NOT NULL REFERENCES notify.country_time_zone (country_code),
    created_at    timestamptz  NOT NULL DEFAULT now()
);

-- R47 (SPEC-PLAT-04 D5): idempotent consumption, per consumer group.
CREATE TABLE notify.inbox (
    consumer_group  varchar(100)  NOT NULL,
    event_id        uuid          NOT NULL,
    processed_at    timestamptz   NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, event_id)
);
CREATE INDEX inbox_processed_ix ON notify.inbox (processed_at);

-- D5/D6 (R45/R46): the grower's message window per case.
CREATE TABLE notify.grower_window (
    case_id           bigint       PRIMARY KEY REFERENCES notify.case_ref (case_id),
    window_ends_at    timestamptz  NOT NULL,
    pending_count     integer      NOT NULL DEFAULT 0 CHECK (pending_count >= 0),
    pending_template  varchar(64),
    pending_status    varchar(40),
    due_at            timestamptz,
    batch_seq         integer      NOT NULL DEFAULT 0 CHECK (batch_seq >= 0),
    updated_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT grower_window_pending
        CHECK ((pending_count = 0) = (due_at IS NULL) AND (pending_count = 0) = (pending_template IS NULL))
);
CREATE INDEX grower_window_due_ix ON notify.grower_window (due_at) WHERE pending_count > 0;

-- Runtime role: least privilege per table (the databases grant full DML by default, so REVOKE first).
REVOKE ALL ON notify.country_time_zone, notify.visible_status, notify.status_label, notify.case_ref, notify.inbox,
              notify.grower_window FROM ${app_role};
GRANT SELECT ON notify.country_time_zone, notify.visible_status, notify.status_label TO ${app_role};
GRANT SELECT, INSERT ON notify.case_ref TO ${app_role};
GRANT SELECT, INSERT, DELETE ON notify.inbox TO ${app_role};
GRANT SELECT, INSERT, UPDATE ON notify.grower_window TO ${app_role};
