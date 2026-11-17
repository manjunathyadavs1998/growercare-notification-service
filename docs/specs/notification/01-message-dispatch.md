# SPEC-NOT-01: Message dispatch, provider ports and the dev mailbox
- **Requirements:** NOT-1 (SMS / WhatsApp / email), IAM-1/IAM-5 (OTP delivery), PRV-5, Phase 1 PRD journeys J1/J2 and decision **D2** (messages stubbed locally)
- **Status:** Implemented
- **Owner:** notification-service (new repository `growercare-notification-service`, port 8085)
- **Follow-up:** SPEC-NOT-02 — case and comment notifications from events (PRD J7: batching, quiet hours, recipient resolution)

## 0. Decisions (to agree with this spec)
| # | Decision | Why |
|---|---|---|
| D1 | **One internal API, `POST /internal/messages`,** for allow-listed services (Phase 1: `grower-service`). The caller sends channel, recipient, template, language and parameters; notification-service renders and dispatches. No public API | Services never format or send messages themselves; one place for templates, providers, limits and logs |
| D2 | **Providers behind ports** (`SmsProvider`, `WhatsAppProvider`, `EmailProvider`). Phase 1 ships **only the `mailbox` adapter**: it stores the rendered message in a dev mailbox instead of sending. Real providers (SMS gateway, WhatsApp BSP, SES) are later adapters behind the same ports | PRD D2: no real SMS locally; tests and the UI can read messages deterministically |
| D3 | **The dev mailbox is a local-only feature:** `GET /dev/mailbox?recipient=…` and a small HTML page list stored messages, newest first. It exists only when `GC_DEV_MAILBOX=true`; the service **refuses to start** with the mailbox enabled together with any real provider, and the flag defaults to false | Playwright and people read OTPs locally; the bodies (which contain codes) can never exist in a real environment |
| D4 | **Delivery log without content:** every message gets a row `{messageId, channel, template, language, category, recipientMasked (last 4 / masked email), status, createdAt, dispatchedAt}` — never the body or the full recipient. The mailbox (D3) is a separate table that holds bodies, local only | PRV-5: an OTP or a phone number must not sit in a production log table |
| D5 | **Templates are resources** (`templates/<template>/<language>.txt`, ICU-style `{placeholders}`), Phase 1 set: `otp.activation` in en, hi, mr, pt. An unknown template or a language without a text is a 422 (no silent fallback for OTPs) | Reviewed texts per language; a missing translation is a defect, not a fallback |
| D6 | **Idempotency:** the caller sends an `Idempotency-Key` (UUID); the same key and body returns the original `messageId` without a second dispatch; another body is 409 | A retried OTP request must not send two codes |
| D7 | **Categories:** `OTP`, `TRANSACTIONAL`, `MARKETING` (Phase 1 accepts `OTP` only). OTPs bypass quiet hours (SPEC-NOT-02) and are never batched | Codes must arrive at once |
| D8 | **Database `gc_notification`, not sharded** in Phase 1 (one delivery-log table, monthly partitions later, `docs/architecture/01_SERVICES/notification-service.md`) | Volume in Phase 1 is OTPs only; partitioning comes with event notifications |

## 1. Behaviour (rules)
| Rule | Given | When | Then |
|---|---|---|---|
| NOT-R1 | allow-listed service client | `POST /internal/messages` with `Idempotency-Key` `{channel: SMS, recipient: "+91…", template: "otp.activation", language: "mr", category: "OTP", params: {code: "123456", minutes: "5"}}` | 202 `{messageId, status: "DISPATCHED"}`; the template is rendered in that language and handed to the channel's provider; a delivery-log row (D4) |
| NOT-R2 | same `Idempotency-Key` and body | retried | 202 with the same `messageId`, `Idempotent-Replayed: true`; no second dispatch |
| NOT-R3 | same key, different body | retried | 409 `IDEMPOTENCY_KEY_REUSED` |
| NOT-R4 | unknown template · language without a text · category other than OTP (Phase 1) · a template placeholder without a parameter | send | 422 `notification.template-unknown` · `notification.language-missing` · `notification.category-unsupported` · `notification.param-missing` |
| NOT-R5 | invalid input: channel not SMS/WHATSAPP/EMAIL, recipient not E.164 (SMS/WhatsApp) or not an email (EMAIL), missing key, malformed JSON | send | 400 `VALIDATION_FAILED` naming the field |
| NOT-R6 | the provider fails (adapter throws) | send | 503 `DEPENDENCY_UNAVAILABLE`; delivery-log row with status `FAILED`; nothing in the mailbox |
| NOT-R7 | user token · service client not allow-listed · no token | send | 401 · 401 · 401 (notification-service has no end users: a user token is refused as an unknown account before authorization) |
| NOT-R8 | `GC_DEV_MAILBOX=true` | `GET /dev/mailbox?recipient=+91…` | 200 `{items: [{messageId, channel, recipient, template, language, body, createdAt}]}` newest first (at most 50); `GET /dev/mailbox/ui` shows the same as a page |
| NOT-R9 | `GC_DEV_MAILBOX` false or unset | `GET /dev/mailbox…` | 404 (the routes do not exist) |
| NOT-R10 | `GC_DEV_MAILBOX=true` and a real provider configured | start | refuses to start, naming the conflict (D3) |
| NOT-R11 | any message | anywhere outside the dev mailbox | the body, the code and the full recipient appear in no log, no delivery-log row and no response other than the mailbox (D4) |

## 2. Examples
| Example | Rule | Input | Expected |
|---|---|---|---|
| NOT-E1 | R1 | grower-service token; SMS, `+919876543210`, `otp.activation`, `mr`, `{code: "482913", minutes: "5"}` | 202; mailbox holds one message whose body is the Marathi text with `482913` and `5`; log row `recipientMasked "******3210"`, status `DISPATCHED`, no body |
| NOT-E2 | R1 | same in `en`, `hi`, `pt` | 202; body in that language |
| NOT-E3 | R2 · R3 | E1 replayed · same key with another code | 202 same `messageId`, one mailbox entry · 409 |
| NOT-E4 | R4 | template `otp.unknown` · language `ta` · category `MARKETING` · params without `code` | 422 with the matching rule |
| NOT-E5 | R5 | channel `FAX` · recipient `98765` · EMAIL to `not-an-email` · no Idempotency-Key | 400 naming `channel` / `recipient` / `recipient` / `Idempotency-Key` |
| NOT-E6 | R6 | provider stub set to fail | 503; log row `FAILED`; mailbox unchanged |
| NOT-E7 | R7 | care.in user token · `integration-service` client · none | 401 · 401 · 401 |
| NOT-E8 | R8 | after E1 and E2, `GET /dev/mailbox?recipient=%2B919876543210` | 4 items, newest first, bodies included |
| NOT-E9 | R9 | mailbox disabled | 404 for `/dev/mailbox` and `/dev/mailbox/ui` |
| NOT-E10 | R10 | `GC_DEV_MAILBOX=true`, `GC_SMS_PROVIDER=twilio` | startup fails naming both settings |
| NOT-E11 | R11 | after E1: captured logs, `delivery_log` rows, response bodies | `482913` and `9876543210` appear only in the mailbox table |

## 3. API contract
```
POST /internal/messages        Idempotency-Key: <uuid>
  {channel: SMS|WHATSAPP|EMAIL, recipient, template, language, category: OTP, params: {string: string}}
  202 {messageId, status} · 400 · 401 · 403 · 409 · 422 · 503
GET  /dev/mailbox?recipient=   (only with GC_DEV_MAILBOX=true)   200 {items: [MailboxMessage]}
GET  /dev/mailbox/ui           (same)                           200 text/html
```
OpenAPI exported to `contracts/notification-service.openapi.json` (the `/dev` routes are excluded from the contract).

## 4. Data & events
- `gc_notification`: `delivery_log` (D4), `message_idempotency` (key, caller, request hash, message id), `dev_mailbox` (D3; table always exists, written only by the mailbox adapter).
- No events published or consumed in NOT-01 (event consumption is SPEC-NOT-02).
- Configuration (env only, SPEC-PLAT-06): DB, OIDC, `GC_INTERNAL_CLIENTS=grower-service`, `GC_DEV_MAILBOX` (default false), `GC_SMS_PROVIDER` / `GC_WHATSAPP_PROVIDER` / `GC_EMAIL_PROVIDER` (default `mailbox` only when the dev mailbox is on; otherwise required).

## 5. Security
| Concern | Rule |
|---|---|
| Who may send | allow-listed service clients only (R7); no user may send messages |
| Secrets in messages | bodies only in the local dev mailbox; never logged (R11) |
| Local-only features | mailbox routes absent unless enabled; cannot coexist with real providers (R10) |

## 6. Traceability
| Rule / Example | Test |
|---|---|
| R1, R4 / E1, E2, E4 | `TemplateRendererTest` (every language; unknown template, language, parameter), `MessageDispatchIT` |
| R1–R6 / E1–E6 | `MessageDispatchIT` (mailbox provider wrapped so it can fail) |
| R7 / E7 | `MessageDispatchIT#onlyAllowListedServicesMaySend` |
| R8 / E8 | `MessageDispatchIT#NOT_E8_theDevMailboxListsMessagesNewestFirst` |
| R9 / E9 | `DevMailboxDisabledIT` (routes absent; without a provider a send fails closed with 503) |
| R10 / E10 | `ProviderConfigurationTest` |
| R11 / E11 | `MessageDispatchIT#NOT_E1_E11_…` (captured output, delivery-log row, response body) |
| §3 contract | `NotificationOpenApiContractIT` (`/dev/**` excluded) |
