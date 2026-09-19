# Changelog

All notable changes to Nkap are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.0.0/); versioning follows
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- `--nkap.apikey.revoke --nkap.apikey.id=<id>`: retires an API key immediately, without
  deleting its row. `api_key.revoked_at` marks it, so the merchant, the label and
  `last_used_at` survive for whoever asks later why a caller stopped working; the key stops
  authenticating on its very next request, not at some later cache expiry (there is none).
  Rotation is provisioning a new key and revoking the old one — no separate command needed
  (issue #112).
- `CallbackEvent.unattributed(String, ProviderStatus)`: lets an adapter for an operator whose
  callback never carries any reference Nkap chose (M-Pesa's does not) report the operator's
  own reference instead. `provider-api`'s existing two-argument
  `CallbackEvent(ReferenceId, ProviderStatus)` is unchanged and every adapter written against
  `1.0.0` keeps compiling. `CallbackController` resolves an unattributed callback against the
  `reference ↔ provider_reference` association `PaymentRepository` already builds for
  `query()` (issue #96), through a new `PaymentRepository.findByProviderReference` (never a
  guess between more than one match) and a matching index
  (`V11__provider_reference_lookup`). A callback naming a provider reference nothing matches
  — most likely a submission whose response was lost — is still `202` with nothing written,
  the same as an unknown reference and logged with the same "at most once, ever" bound, under
  its own counter reason: reaching this branch takes only an invented value, not a correct
  guess, exactly like an invented Nkap reference, so it gets the same protection against being
  used to flood the log (ADR 0011 §2; issue #149).

### Changed

- `UntrustedCallbackException`'s javadoc no longer says it is thrown when a callback "fails
  authentication" — no operator observed against this project offers a callback anything to
  authenticate. The type itself is unchanged (ADR 0011's own "Alternatives rejected": a
  published type is not renamed for a naming preference).

## [1.1.0] - 2026-09-18

### Added

- `nkap.public-base-url`: the deployment-level property that lets MTN call this gateway
  back at all — without it, no deployment of Nkap can receive a callback. Unset (the
  default) is safe: no `X-Callback-Url` is ever sent, and a payment still resolves through
  the reconciler, only slower. **Read this before setting it: naming a host that does not
  match `providerCallbackHost` as recorded at MTN API-user creation fails every submission
  with `INVALID_CALLBACK_URL_HOST`.** See `docs/configuration-reference.md`'s entry for it
  (issue #116).
- `nkap_callback_received_total`, `nkap_callback_rejected_total` and
  `nkap_callback_confirmed_total`: counters for traffic on the callback endpoint, which
  previously exposed none (issue #129).

### Changed

- **`MtnStatusMap.stateFor` now reads both `status` and `reason` instead of `reason`
  alone.** A payment MTN answered conclusively — `status: FAILED, reason:
  INTERNAL_PROCESSING_ERROR` — used to sit `UNKNOWN`, get chased by the reconciler, and
  eventually escalate; it now reaches `FAILED` immediately, on the first query. **Anyone
  alerting on a count of `FAILED` payments will see a step change upward on upgrade;
  anyone alerting on `nkap_payment_escalated_total` will see one downward.** Neither is a
  new failure mode — both are the same corrected verdicts moving from one bucket to the
  other (issue #115).
- A payment whose operator answers `status: CREATED` was reported as `UNKNOWN` and is now
  reported as `PENDING` (issue #117).
- **The reconciler's per-attempt "not conclusive, changing nothing" line moved from INFO to
  DEBUG.** Anything parsing logs for that line stops seeing it on upgrade. The counters
  added above, and the reconciler's own escalation line (unchanged, still WARN), are what
  to watch instead (issue #129).

### Fixed

- `compose.yaml` and `nkap-standalone.compose.yaml` no longer share a Compose project.
  Both used to resolve to project `nkap`, so a machine that had run one and then the other
  shared one PostgreSQL volume — PostgreSQL applies `POSTGRES_PASSWORD` only when it
  initialises an empty data directory, so whichever file touched the volume first kept its
  password and the other could never connect (issue #123). `compose.yaml` now names its own
  project, `nkap-demo`; `nkap-standalone.compose.yaml` is unchanged, so no real deployment's
  project or volume is renamed by this. **If you have run `compose.yaml` before this
  release, its old `nkap_*` volume is orphaned by the rename** — unused, not deleted; remove
  it by hand with `docker volume rm` if you want the disk space back.
- A wrong database password now fails with a one-line diagnosis ("the database answered and
  rejected these credentials") instead of a bare Flyway stack trace.

### Upgrading

The first release carrying a schema migration: V9 adds `payment.provider_base_url`, the
installation base URL a payment was actually submitted against, so a simulated settlement
and a real one stop being indistinguishable in the ledger (issue #122).

**Forward is automatic and additive.** Flyway runs V9 at startup — one nullable column, one
trigger scoped to `BEFORE UPDATE OF provider_base_url` — and rewrites nothing already there.

**Rolling the image back to 1.0.0 against a V9 database works.** Verified against the
published 1.0.0 image, 2026-09-18: Flyway warns and proceeds.

```
WARN  Schema "public" has a version (9) that is newer than the latest available migration (8) !
INFO  Schema "public" is up to date. No migration necessary.
INFO  Started NkapServerApplication in 1.358 seconds
```

1.0.0 never names `provider_base_url` — not in its `INSERT`, its `ON CONFLICT` `SET` list,
or its `SELECT` — so it neither writes the column nor fires the trigger.

**The cost of rolling back, which the same run makes visible:** payments created while a
deployment is back on 1.0.0 carry no provenance. `provider_base_url` stays `NULL`, and those
rows become indistinguishable from the pre-migration ones — the same honest gap #122 chose,
open again for as long as the rollback lasts. Nothing backfills it afterwards. Know this
before rolling back, not after.

## [1.0.0] - 2026-09-14

<!--
  #104 deliberately left this undated -- an entry dated in advance is a small lie this
  repository has spent a week not telling. #106 is the release commit the v1.0.0 tag points
  at, so the wrinkle that instruction was waiting for has arrived: dating this afterwards
  would leave the released tree containing an undated entry, disagreeing with its own
  release. The date above is the day this commit merges, and the tag follows the same day --
  not the rule being broken, the rule meeting the moment it was written for.
-->

There is no previous release, so nothing here has *changed* — this entry says what 1.0.0
**is**, what it deliberately does not do, and what stability starts to mean once this tag
exists. It replaces the usual Added/Changed/Fixed categories for exactly that reason: every
one of them would just say "added," as many pull requests wide as it took to build this,
which is accurate and useless — nobody reads a list that long to learn what a thing is.

### What this release is

- **The gateway.** An HTTP API that creates a payment, hands it to an operator adapter, and
  reports its state with caller-facing idempotency and honest status codes — a call that did
  not answer is `202` and `UNKNOWN`, never `500` and never `FAILED`.
- **A double-entry ledger**, its invariants enforced as PostgreSQL constraints rather than
  only in application code: append-only, zero-sum, one currency per entry, integer minor
  units. A mistake is corrected by a reversing entry, never a rewrite.
- **MTN MoMo, both products.** Collections and Disbursements, multi-country in one
  deployment (several installations, several currencies, one adapter per country), the
  complete status and error mapping documented and checked against the code
  (`docs/providers/mtn.md`).
- **Refunds as reversing entries, never an edit.** A refund is a `DISBURSE` payment naming
  the collection it refunds, reusing the same state machine, settlement, reconciler and
  outbox; its amount is reserved the moment it is created, not only once it settles.
- **The reconciler**, for every payment a timeout or a silence left unresolved: exponential
  backoff, a configurable window, escalation to a human — never an automatic verdict.
- **Outgoing webhooks**: HMAC-SHA256 signed, at-least-once delivery, exponential backoff, a
  dead-letter queue, and replay from the console or the admin API.
- **Statement reconciliation**: import an operator statement, compare it to the ledger,
  produce a discrepancy report.
- **The conformance kit** every adapter must pass before it is merged — the actual mechanism
  for accepting an operator nobody on this project has an account with.
- **Published images** (`ghcr.io/deval123/nkap-gateway`, `nkap-simulator`, multi-arch) and a
  **Helm chart** (`charts/nkap`) that accepts no credential value inline, only the name of a
  `Secret` the operator created.
- **Documentation this repository executes, not merely publishes**: `docs/openapi.yaml` and
  `docs/integration-guide.md` against the running application (`OpenApiSpecIT`,
  `examples/run-integration-guide.sh`), `docs/configuration-reference.md` against the
  `@ConfigurationProperties` classes (`ConfigurationReferenceTest`), and
  `docs/providers/mtn.md`'s status table against `MtnStatusMap.TABLE`
  (`MtnStatusMappingDocTest`).

### What it deliberately does not do

`docs/positioning.md`'s *What is not in scope, and why it is written down* is the full
argument for each of these; this only names them.

- Hold funds, become a licensed payment institution, or do KYC, AML or sanctions screening.
- Convert between currencies — several are supported, converting between them is not.
- Carry a redirect-style operator (a payer sent to a URL, a QR code or a deeplink rather
  than pushed to their handset) — declined for this release, not forgotten; see the
  Orange Money review (`docs/providers/orange-money.md`).
- Rate limit callers, offer a key-rotation or key-revocation *command* (a direct database
  delete is the only way today), or keep an audit trail of which key did what.

`docs/security-notes.md` covers all three of the last group in full, alongside what an
operator must do that Nkap cannot (TLS termination, network placement, database access).

### What stability means from this tag onward

Some of the following are promises this project is making about the future; some are
today's behaviour, worth knowing but not guaranteed to stay exactly this shape. Said
separately on purpose — a changelog that promises more than the project intends to keep is
worse than one that promises nothing.

**Guarantees:**

- **`ProviderAdapter` is a published contract.** Widening it — adding a parameter, changing
  a method's shape — breaks every external adapter, which is exactly why issue #96 landed
  *before* this tag rather than after: ADR 0008's whole argument is that the contract is
  cheapest to change while there is one adapter to change it with, and after this tag that
  window is closed for good.
- **`Currency` only grows.** A member is a currency amounts may already exist in; removing
  one is not a documentation change, it is a compile error for anyone who settled in it and
  a deserialisation failure for rows already in `ledger_entry` (`Currency`'s own javadoc).
- **The webhook payload's fields are contract, not a refactorable record.**
  `PaymentEventPayload`'s own javadoc says renaming or removing a field is a breaking change
  to every integration, not a refactor — that was true before this tag and stays true after
  it.
- **Problem types and `docs/openapi.yaml` describe the HTTP surface**, and `OpenApiSpecIT`
  keeps that true on every build — a documented path, method, status code or field is one
  this project checks itself against, not one a reader has to take on faith.

**Current behaviour, not a guarantee:**

- **Migrations go forward.** Every migration has a hand-written, tested inverse
  (`MigrationRollbackIT` runs each one), which proves an inverse exists — it does not prove
  two versions of the application can run against one schema at the same time. Running more
  than one replica, or a rolling upgrade, rests on every migration staying additive and
  backward-compatible with the previous release; `charts/nkap/README.md` states that as the
  constraint an operator relies on, not as something this project is promising to enforce
  for every migration to come.

[Unreleased]: https://github.com/deval123/nkap/compare/v1.1.0...HEAD
[1.1.0]: https://github.com/deval123/nkap/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/deval123/nkap/releases/tag/v1.0.0
