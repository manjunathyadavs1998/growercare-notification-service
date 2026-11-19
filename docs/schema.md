# notification-service database (`gc_notification`, schema `notify`)

Not sharded in Phase 1 (SPEC-NOT-01 D8; case notifications, SPEC-NOT-02, keep one small row per case). Roles: `gc_notification_owner` (Flyway), `gc_notification_app` (runtime: SELECT, INSERT only; append-only tables).

| Table | Purpose | Key columns |
|---|---|---|
| `delivery_log` | every message, **without body or full recipient** (D4) | `message_id` PK, `caller`, `channel`, `template`, `language`, `category`, `recipient_masked`, `status` (DISPATCHED / FAILED), `created_at` |
| `message_idempotency` | replays and key reuse (D6) | `idempotency_key` PK, `caller`, `request_hash` (SHA-256), `message_id` → delivery_log |
| `case_ref` | SPEC-NOT-02 D4: the only case data kept, from `case.created` | `case_id` PK, `grower_id`, `country_code` → country_time_zone |
| `inbox` | SPEC-NOT-02 R47: idempotent consumption of `case.case.events` | (`consumer_group`, `event_id`) PK, `processed_at` |
| `grower_window` | SPEC-NOT-02 D5/D6: batching and quiet hours, one row per case | `case_id` PK → case_ref, `window_ends_at`, `pending_count`, `pending_template`, `pending_status`, `due_at` (partial index where pending), `batch_seq` |
| `visible_status`, `status_label`, `country_time_zone` | SPEC-NOT-02 reference data (D3, D6) | `status_code` PK + `closes`; (`status_code`, `language`) PK + `label`; `country_code` PK + `time_zone` |
| `dev_mailbox` | **local only** (D3): rendered bodies written by the mailbox provider | `message_id` PK → delivery_log, `recipient`, `body`; index `(recipient, created_at DESC)` |

## Migrations
- V1: the three tables, grants (SELECT, INSERT) and explicit REVOKE of UPDATE, DELETE, TRUNCATE for the app role.
- V2 (SPEC-NOT-02): case notifications. REVOKE ALL first, then per table: reference data SELECT; `case_ref` SELECT, INSERT; `inbox` SELECT, INSERT, DELETE (retention); `grower_window` SELECT, INSERT, UPDATE. Seeds: IN/BR time zones, visible statuses and their names in en/hi/mr/pt.
