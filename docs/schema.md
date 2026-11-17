# notification-service database (`gc_notification`, schema `notify`)

Not sharded in Phase 1 (OTP messages only, SPEC-NOT-01 D8). Roles: `gc_notification_owner` (Flyway), `gc_notification_app` (runtime: SELECT, INSERT only; append-only tables).

| Table | Purpose | Key columns |
|---|---|---|
| `delivery_log` | every message, **without body or full recipient** (D4) | `message_id` PK, `caller`, `channel`, `template`, `language`, `category`, `recipient_masked`, `status` (DISPATCHED / FAILED), `created_at` |
| `message_idempotency` | replays and key reuse (D6) | `idempotency_key` PK, `caller`, `request_hash` (SHA-256), `message_id` → delivery_log |
| `dev_mailbox` | **local only** (D3): rendered bodies written by the mailbox provider | `message_id` PK → delivery_log, `recipient`, `body`; index `(recipient, created_at DESC)` |

## Migrations
- V1: the three tables, grants (SELECT, INSERT) and explicit REVOKE of UPDATE, DELETE, TRUNCATE for the app role.
