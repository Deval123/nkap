# Changelog

All notable changes to Nkap are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.0.0/); versioning follows
[Semantic Versioning](https://semver.org/).

## [Unreleased]

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

[Unreleased]: https://github.com/deval123/nkap/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/deval123/nkap/releases/tag/v1.0.0
