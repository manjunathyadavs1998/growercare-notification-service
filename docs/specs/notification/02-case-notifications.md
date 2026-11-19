# SPEC-NOT-02: Case notifications (PRD J7)
- **Requirements:** NOT-1 (SMS/WhatsApp/email), PRV-5, PRD journey **J7** ("everyone is kept informed"), M5
- **Status:** Implemented
- **Owner:** notification-service (consumes case-service events; contacts from grower-service SPEC-GRW-07 and IAM SPEC-IAM-05)
- **IDs:** rules NOT-R40+, examples NOT-E40+

## 0. Decisions (to agree with this spec)
| # | Decision | Why |
|---|---|---|
| D1 | **Consume `case.case.events`** (case-service outbox → Debezium → Kafka, key = case id; `case.created`, `case.status_changed`, `case.assigned`, `comment.posted`) with an **inbox** (idempotent by event id) and a DLQ, like case-service's consumers | Events only through the outbox (convention 3) |
| D2 | **Who is told what:** the grower (SMS) when their case is **created**, a **public** comment by staff or a partner is posted, the status moves to a **customer-visible** status, or the case **closes**; the assignee (email) when a case is **assigned** to them. Internal notes, the grower's own comments and non-visible statuses tell nobody | PRD J7 |
| D3 | **Customer-visible statuses** are reference data (`notify.visible_status`, with a `closes` flag, and their names per language in `notify.status_label`; seeded: TRIAGED, NEED_INFO, FIELD_INVESTIGATION, ROOT_CAUSE_DETERMINED, COMPENSATION_APPROVED, NO_COMPENSATION, ADVICE_GIVEN, RESOLVED_BY_ADVICE, REOPENED and every closing status); a country profile can change it later | Convention 4; staff-internal steps (CLASSIFIED, SAMPLE_COLLECTED, LAB_ANALYSIS, ESCALATED…) are not news for the grower |
| D4 | **Contacts are fetched at send time, never stored:** the grower's phone and language from grower-service `GET /internal/growers/{id}/contact` (SPEC-GRW-07), the staff email and language from IAM `GET /internal/users/by-id/{id}/contact` (SPEC-IAM-05), with notification-service's own client-credentials token. notification-service keeps only `notify.case_ref (case_id, grower_id, country_code)` from `case.created` | PRV-5: the phone stays encrypted in grower-service; nothing personal is copied |
| D5 | **Batching: at most one message per case and recipient per 5 minutes.** The first event sends at once; events in the next 5 minutes are merged into one message sent when the window ends ("2 updates on your case"; a single waiting event is sent as itself). The grower is the only batched recipient, so the window is one row per case (`notify.grower_window`); a job releases due windows every 15 s (`GC_CASE_NOTICE_RELEASE_INTERVAL_MS`) | PRD J7 |
| D6 | **Quiet hours 21:00–07:00 in the country's time zone** (reference data): grower messages due then wait until 07:00; staff emails are not delayed; OTPs (SPEC-NOT-01) are never affected | PRD J7 |
| D7 | **Messages name no personal or case details:** the template says what happened ("there is a new reply on your case") with a link to the case in the grower app (`GC_GROWER_APP_URL` + `/cases/{id}`; the staff email links to the staff console, `GC_STAFF_APP_URL` + `/cases/{id}`), in the grower's language (en, hi, mr; pt for Brazil). The events carry no case number or title, and the message does not need them | Short SMS; no event contract change |
| D9 | **The window and quiet hours are environment settings with the spec's defaults:** `GC_CASE_NOTICE_WINDOW` (ISO-8601, default PT5M, at most PT1H) and `GC_CASE_NOTICE_QUIET_HOURS` (default true). Only the local stack changes them (a 20-second window, quiet hours off), so the J7 smoke check and Playwright FE-E407 run in seconds at any hour | Deterministic end-to-end runs (SPEC-FE-04); production keeps D5/D6 |
| D8 | **Cases created before NOT-02** have no `case_ref`: their later events are skipped with a counter (Phase 1 only has new cases on a fresh stack) | No backfill job for a pilot |

## 1. Behaviour (rules)
| Rule | Given | When | Then |
|---|---|---|---|
| NOT-R40 | `case.created` | consumed | `case_ref` stored; the grower is sent `case.created` (subject to D5/D6) |
| NOT-R41 | `comment.posted` with `visibility PUBLIC` and `authorRole` not GROWER | consumed | the grower is sent `case.reply` |
| NOT-R42 | `comment.posted` INTERNAL, or by the grower | consumed | nothing is sent |
| NOT-R43 | `case.status_changed` to a visible status (D3) · to a closing status | consumed | `case.status` with the status name in the grower's language · `case.closed` |
| NOT-R44 | `case.assigned` | consumed | the assignee is emailed `staff.assigned` (no batching or quiet hours) |
| NOT-R45 | a second grower message for the same case within 5 minutes | due | merged; one `case.updates` message at the end of the window with the count |
| NOT-R46 | a grower message due between 21:00 and 07:00 country time | due | sent at 07:00 |
| NOT-R47 | the same event delivered twice | consumed | handled once (inbox) |
| NOT-R48 | contact lookup 404 (grower inactive or deleted) · 5xx or unreachable | send | skipped and counted (`notification.case.skipped{reason=contact-missing}`) · on the event path the event is retried with back-off, then goes to `dlq.notification-service.case-notices` (its inbox row rolls back); on the batch path the window stays pending and is retried a minute later |
| NOT-R49 | any message | anywhere | the phone, email and body appear only in the dev mailbox (as NOT-01 R11); contact objects never print them |
| NOT-R51 | `GC_CASE_NOTICE_WINDOW=PT20S`, `GC_CASE_NOTICE_QUIET_HOURS=false` | grower notices | the window is 20 s; nothing waits for 07:00. An empty, zero, negative or over-an-hour window refuses to start |
| NOT-R50 | an event for a case without `case_ref` (created before NOT-02, D8) | consumed | nothing is sent; counted (`notification.case.skipped{reason=case-unknown}`) |

## 2. Examples
| Example | Rule | Input | Expected |
|---|---|---|---|
| NOT-E40 | R40 | `case.created` for G1 (language mr) at 10:00 IST | one SMS to G1 in Marathi with the case link |
| NOT-E41 | R41/R42 | care.in public reply · care.in internal note · grower comment | SMS for the reply only |
| NOT-E42 | R43 | SUBMITTED → TRIAGED · TRIAGED → CLASSIFIED · → CLOSED | `case.status` "under review" · nothing · `case.closed` |
| NOT-E43 | R44 | assigned to agro.in | email to agro.in |
| NOT-E44 | R45 | created at 10:00:00, reply at 10:01, status at 10:03 | one SMS at 10:00, one "2 updates" SMS at 10:05 |
| NOT-E45 | R46 | reply at 22:30 IST · 06:59 · 07:00 · 20:59 · 21:00 | sent at 07:00 the next day · 07:00 · at once · at once · 07:00 the next day |
| NOT-E46 | R47 | the reply event twice | one message |
| NOT-E47 | R48 | grower contact 404 · grower-service 5xx | no message, counter +1, no retry · no message, the event lands on the DLQ, no inbox row |
| NOT-E49 | R50 | a reply on a case notification-service never saw created | no message, counter +1 |
| NOT-E50 | R51 | window PT20S, quiet hours off: created 22:30 IST, reply 22:30:05 · window PT0S | created at once, the reply at 22:30:20 · refused at start |
| NOT-E48 | **smoke / J7** | the J5 journey (create, internal note, public reply, triage, assign) | mailbox: grower gets created + reply/update messages in their language, no note; agro.in gets the assignment email |

## 3. Configuration
New required variables: `KAFKA_BOOTSTRAP_SERVERS`, `GROWER_BASE_URL`, `IAM_BASE_URL`, `OIDC_TOKEN_URI`, `SERVICE_CLIENT_ID` / `SERVICE_CLIENT_SECRET` (Keycloak client `notification-service`, audience `gc-internal`; it must be in grower-service's and IAM's `GC_INTERNAL_CLIENTS` and `GC_CONTACT_CLIENTS`), `GC_GROWER_APP_URL`, `GC_STAFF_APP_URL`. Optional: `GC_EVENTS_CONSUMERS_ENABLED` (default true), `GC_CASE_NOTICE_RELEASE_ENABLED` (true), `GC_CASE_NOTICE_RELEASE_INTERVAL_MS` (15000).

## 4. Data & events
Migration V2 (schema `notify`, not sharded):

| Table | Purpose | App role |
|---|---|---|
| `case_ref (case_id, grower_id, country_code)` | from `case.created`; the only case data kept (D4) | SELECT, INSERT |
| `inbox (consumer_group, event_id, processed_at)` | idempotent consumption (R47) | SELECT, INSERT, DELETE |
| `grower_window (case_id, window_ends_at, pending_count, pending_template, pending_status, due_at, batch_seq)` | batching and quiet hours (D5, D6) | SELECT, INSERT, UPDATE |
| `visible_status (status_code, closes)` · `status_label (status_code, language, label)` · `country_time_zone (country_code, time_zone)` | reference data (D3, D6; seeded IN Asia/Kolkata, BR America/Sao_Paulo) | SELECT |

Templates `case.created`, `case.reply`, `case.status` (`{status}`), `case.closed`, `case.updates` (`{count}`), `staff.assigned` × en/hi/mr/pt, each with `{link}`. Messages are TRANSACTIONAL, sent through the NOT-01 dispatch (delivery log, idempotency key derived from the event id or the window's batch number, so a redelivery never sends twice).

## 6. Traceability
| Rule / Example | Test |
|---|---|
| R40–R48, R50 / E40–E47, E49 | `CaseNotificationsIT` (Kafka Testcontainer, stubbed contact directory, fake clock) |
| R41–R44 / E41–E43 (decisions) | `CaseNoticeRulesTest` |
| R45/R46 / E44, E45 (window and quiet-hour arithmetic) | `GrowerWindowTest`, `QuietHoursTest` |
| R51 / E50 | `GrowerWindowTest` (policy rows), `NoticePolicyTest` |
| R48 (HTTP: 200 · 404 · 5xx, service token) | `HttpContactDirectoryTest` |
| R49 | `ContactHygieneTest`; the NOT-01 delivery-log rows in `MessageDispatchIT` |
| E48 | local-platform `smoke.sh` and Playwright FE-E407 |
