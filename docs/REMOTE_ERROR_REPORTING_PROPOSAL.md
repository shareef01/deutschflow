# Remote handled-error reporting proposal

Status: proposed, 6 November 2026. No SDK, endpoint, credentials, or collection is
enabled by this document. Android scope; web reporting requires its own design.

## Purpose and boundary

Detect recurring failures that are handled without crashing: saving study results,
reading/saving conversations, backup import/export, and startup housekeeping.
Continue using Play vitals for eligible production crash/ANR coverage. Keep the
existing user-visible errors and cancellation behavior. Reporting must never delay
a save, retry an AI request, or become a reason for the app to crash.

Start with counts and fixed categories. Raw exceptions and stack traces are outside
the first rollout: throwable messages, causes, filenames and provider bodies can
contain user content or credentials. There is no automatic logcat capture,
breadcrumb recording, screenshot capture, session replay, or user/device identity.

## Proposed event contract

```json
{
  "schemaVersion": 1,
  "appVersion": "1.0.0",
  "versionCode": 10000,
  "sdkMajor": 37,
  "operation": "study.save_review",
  "failureCategory": "storage_write",
  "count": 3
}
```

Fields are an allowlist. operation is a compile-time enum (study.save_review,
roleplay.read, roleplay.save, backup.import, backup.export, startup.schedule,
startup.timezone, startup.key_migration). failureCategory is a fixed enum
(storage_read, storage_write, malformed_backup, crypto_unavailable,
network_unavailable, provider_rejected, unexpected). Values are mapped locally;
do not serialize Exception, arbitrary maps, URLs, paths, request/response objects,
or free-form strings. Exclude transcripts, vocabulary, prompts, conversation
history, API keys, backup passwords and raw HTTP responses. Never report normal
cancellation. Expected validation failures should remain local unless a distinct
product need justifies an aggregate category.

No installation/session/user identifier, device model, locale, exact event time,
or network address belongs in the payload. The receiving service can still see
the connection IP; configure infrastructure/access-log retention explicitly and
do not claim collection is anonymous merely because the payload has no ID.

## Collection and delivery

Recommended initial behavior: off by default, with a clearly described opt-in in
Settings. Consent covers diagnostic categories and transport metadata; learning
features work without it. Do not infer consent from microphone permission or Play
vitals participation. Revoking consent deletes pending aggregates and cancels the
reporting worker. Recheck consent immediately before sending each batch.

Aggregate counts in app-private storage, capped at 50 operation/category/version
buckets and 7 days. Coalesce duplicates, cap counts, and send no more than one
small batch per day over HTTPS. Use WorkManager network constraints and bounded
backoff; drop expired data. Reporting failures must not recursively report
themselves, append payloads to logs, or create an unbounded queue.

The server must reject unknown fields/enums, oversized batches, unsupported schema
versions, and invalid counts. Use service-side rate limits and an ingestion-only
public client token if needed; a token embedded in an APK is not a secret or proof
of identity. Administrative credentials stay off-device. Limit dashboard access
to named maintainers, with access review and auditable changes.

## Destination and retention decision

Choose an existing organization-controlled collector if one is available and can
enforce this schema. Otherwise compare managed ingestion and self-hosting against
the same requirements: processing region, IP/access-log handling, deletion,
retention controls, access controls, export, incident response, and cost ceiling.
No vendor is selected here; none is connected to this project.

Proposed server retention: 30 days for incoming aggregates, 90 days for summaries
containing only app version, operation, category and totals. Retention includes
backups. Configure deletion before rollout. With no device identity, individual
server-side lookup/deletion may be impossible; document that limitation accurately.
Review the chosen provider's terms, applicable obligations, privacy notice and
Play Data safety disclosures against actual deployed behavior before collection.

## Implementation and acceptance

Add an injected ErrorReporter interface with a no-op default and typed operation/
category values. Wire explicit handled-error boundaries; do not add an uncaught
exception handler or replace coroutine cancellation semantics. Avoid duplicate
reporting between launchGuarded and its caller. Tests must demonstrate:

- disabled/revoked consent sends nothing and clears queued data;
- raw exception text and user/credential canaries cannot enter the payload;
- cancellation is excluded, duplicate counts coalesce, and queue/retention bounds hold;
- offline delivery, retries, process death and collector failures preserve app behavior;
- the collector rejects unrecognized fields and honors deletion/retention policy.

Validate against a test collector using synthetic failures before production.
Start with an explicitly consenting internal cohort, verify received fields and
infrastructure logs, then review volume and usefulness before broader rollout.
Maintain a kill switch that disables ingestion and a client configuration that
disables future collection without enabling it for users who did not opt in.

## Decisions required before implementation

Confirm the reporting owner and destination, processing region and retention,
opt-in wording and settings flow, operating cost limit, and supported event set.
Approve the concrete collector configuration and client implementation after
synthetic end-to-end validation. This proposal authorizes no data transmission.
