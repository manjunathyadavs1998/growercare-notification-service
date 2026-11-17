# growercare-notification-service

Message dispatch for GrowerCare: SMS, WhatsApp and email through provider ports, templates per language, and a local dev mailbox. One of the independent service repositories of the GrowerCare platform (ADR-017).

- **Spec:** [`docs/specs/notification/01-message-dispatch.md`](docs/specs/notification/01-message-dispatch.md)
- **Port:** 8085 · **Image:** `ghcr.io/manjunathyadavs1998/notification-service`
- **Configuration:** environment variables only, see [`.env.example`](.env.example)

## Build and run
```bash
mvn -B verify                                   # tests (Docker for Testcontainers), coverage gate input
cp .env.example .env && mvn -B package jib:dockerBuild -DskipTests && docker compose up -d
```
The shared infrastructure (Postgres, Keycloak) comes from `growercare-local-platform`.

## Dev mailbox (local only)
With `GC_DEV_MAILBOX=true`, messages are stored instead of sent. Read them at
`http://localhost:8085/dev/mailbox/ui?recipient=%2B919876543210` (page) or `/dev/mailbox?recipient=…` (JSON, used by Playwright and `smoke.sh`).
