# Changelog

All notable changes to Nkap are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.0.0/); versioning follows
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- **Credentials may come from files named after the variables they replace.** The gateway
  imports `/run/secrets` (`NKAP_SECRETS_DIR` moves it). A file there named exactly like a
  variable, such as `NKAP_PROVIDER_MTN_CM_API_KEY`, is read where that variable would be.
  `compose.yaml` now mounts its MTN placeholders this way, so `docker inspect` no longer prints
  them. Nothing changes for a deployment using variables, with or without the directory. A name
  set both as a variable and as a file fails startup rather than one quietly winning. A file
  for an undeclared slot fails startup exactly as the variable does. Credentials are still read
  once, at startup: replacing one, from either source, takes a restart, except M-Pesa's three
  credentials as files (next entry). The Helm chart is unchanged.
- **A rotated M-Pesa credential file takes effect without a restart.** The passkey, the
  Consumer Key and the Consumer Secret, supplied as files, are read again when their file
  changes: a different real path, modification time or size. That covers a file replaced in
  place, whose time changes, and a Kubernetes Secret update, which re-points a link to a new
  directory: a new real path and a new time. The next request uses the new passkey, and
  a new Consumer Key or Secret replaces the
  bearer token obtained with the old pair. Nothing to do differently: rotate the file, and the
  running gateway follows. A rotated value that is unreadable or invalid does not stop
  payments. It is refused by the same check as at startup, payments keep the last valid
  credentials, one warning names the file, and the new gauge `nkap_credentials_stale_seconds`
  stays above zero until it is fixed. `docs/prometheus-alerts.yml` has a rule for it. Only the
  credentials change: the base URL, shortcode and currency stay as started. A credential set as a
  variable, and every MTN credential, still takes a restart. `MpesaAdapter` gains a constructor
  taking a `Supplier<MpesaProfile>`; its two existing constructors are unchanged. See
  [ADR 0015](docs/adr/0015-credentials-read-at-use.md).
- **The Helm chart mounts M-Pesa's credentials as files by default.** The Secret named by
  `provider.mpesa.installations[0].existingSecret` is mounted read-only at
  `/etc/nkap/credentials`, as three files named after the variables they replace, which is the
  form the gateway re-reads (previous entry). The three credential variables are no longer
  rendered in that form, since the gateway refuses a name supplied both ways. The chart sets
  `NKAP_SECRETS_DIR` to the mount path. Nothing to change in your values: the Secret, its keys
  and the `*SecretKey` overrides are read as before. Measured end to end on `kind` (Kubernetes
  `v1.37.0`), by a CI job that installs this chart: a patched Secret changed the `Password` and
  the Consumer Key the next submissions carried, with no restart, and the staleness gauge stayed
  at zero. The Secret took 55 to 87 seconds to reach the pod on the clusters measured; that is
  theirs, not a general number. Nothing measured talks to Safaricom. `credentialsAs: env` on the installation
  renders the three variables as before, read once at startup, for a cluster that cannot mount
  Secrets as volumes. Any other value fails rendering.

- **M-Pesa is a configured operator.** Safaricom's STK Push, collections only: one Kenya slot,
  `NKAP_PROVIDER_MPESA_KE_*`, registered as `mpesa-ke`. `POST /payments` routes on the country
  its installation states, so a Kenyan payment reaches it and a Cameroonian one reaches MTN. A
  configured M-Pesa installation makes `NKAP_PUBLIC_BASE_URL` mandatory: its callback is the
  only way it resolves a submission whose answer was lost. Read `docs/security-notes.md` §1
  before handling its `passkey`: a single request reveals it, and it is not a key in MTN's
  sense.
- **The Helm chart configures M-Pesa**, credentials by Secret reference only, and refuses to
  render an installation the gateway could not read: more than one, or a country other than
  `ke`. The chart's own first install also works for the first time. A release that lists no
  MTN installation now starts; before, the gateway's own `cm` default built a Cameroon adapter
  with no credentials, and the gateway refused to start.
- **A published M-Pesa simulator image, `ghcr.io/deval123/nkap-simulator-mpesa`**, multi-arch,
  on port 8082 so it runs beside the MTN simulator's 8081. It plays Safaricom Daraja's STK
  Push, collections only: the token route, the submission and the status query, and the
  callback. Scenarios are declared through the same `/_nkap` control plane, or mounted at
  `/etc/nkap/scenario.json` as for the MTN image. It checks no Consumer Key and no `Password`,
  since Safaricom's answer to a wrong one was never observed, and plays no B2C. **Its control
  plane is unauthenticated and `GET /_nkap/received` reports the Consumer Key and Secret it was
  sent, in the clear**, so it is for tests and sandboxes only: never point anything holding real
  credentials at it, and never expose `/_nkap`. The image's own description says the same.

### Changed

- **A deployment that relied on MTN Cameroon's country defaulting to `cm` loses its MTN adapter
  on this upgrade, and nothing says so at startup.** Every slot's country now defaults to blank,
  so a deployment that set `NKAP_PROVIDER_MTN_CM_*` credentials but never set
  `NKAP_PROVIDER_MTN_CM_COUNTRY` no longer builds an `mtn-cm` adapter: the slot is skipped, the
  gateway starts, and the first payment for Cameroon answers that no installation is configured.
  Set `NKAP_PROVIDER_MTN_CM_COUNTRY=cm`. This cannot be a startup failure: a first install with
  no adapter configured is legitimate, and the Helm chart's own first install depends on it. So
  this note is the only warning there is.
- **A deployment that sets a provider environment variable for an installation this gateway
  does not have stops starting after this upgrade.** Remove the variable, or add the slot to
  `application.yml`. Only the declared slots exist: MTN `cm` and `gh`, M-Pesa `ke`. A variable
  such as `NKAP_PROVIDER_MTN_CI_COUNTRY`, one for an operator with no adapter
  (`NKAP_PROVIDER_ORANGE_CM_*`), or a lower-case spelling of a declared one, was read by
  nothing: the gateway started, the installation silently did not exist, and its credentials sat
  in the process unused. The deployments this breaks were already broken and did not know. The
  gateway now fails at startup, before touching the database, naming every offending operator
  and country in one message and never a value. `provider-api` is unchanged. See
  `docs/security-notes.md` §1 for why this is a security change.
- **A deployment whose `nkap.provider.default` names no configured adapter stops starting after
  this upgrade.** The one that will bite is a deployment serving only M-Pesa Kenya that never set
  the default: it falls back to `mtn-cm`, which exists only when MTN Cameroon is configured, so
  `GET /balance` and `GET /account-holders/{msisdn}` answered `500` on every call and nothing said
  so at startup. Set `NKAP_PROVIDER_DEFAULT` (Helm: `provider.default`) to a configured provider,
  `mpesa-ke` in that case. A deployment with no provider configured at all still starts.
  `provider-api` is unchanged.
- **`GET /balance` and `GET /account-holders/{msisdn}` answer what the default provider cannot
  serve with a documented, typed refusal instead of an untyped `500`.** A caller that received
  a generic `500` in these three cases now receives: `501 feature-not-offered` when the default
  provider does not offer the route at all (an M-Pesa-only deployment offers neither);
  `400 operation-not-served` when it does not serve the `operation` named (`DISBURSE` against an
  MTN installation configured for Collections only); and, on `GET /balance`, `400
  unserved-currency` when `currency` is not the one it settles in — the answer `POST /payments`
  already gave. All three are refused before the operator is called, the rule a payment's
  operation already followed (ADR 0013). `docs/openapi.yaml` documents them, and no longer says
  `currency` is sent to the operator unvalidated: it never was. `provider-api` is unchanged.
- **A deployment whose operator credential starts or ends with whitespace or an invisible
  character stops starting after this upgrade.** Such a deployment was already broken: the
  credential was sent as configured, and the operator refused every request. Nothing in any log
  said why, because the character is invisible and the value is never printed. Now `MtnProfile`
  and `MpesaProfile` refuse it at startup, naming the field and which end, never the value,
  whether it came from a variable or a file. This covers a no-break space pasted from a web
  portal, a byte-order mark, and a credential file saved as UTF-16. Remove the character, and
  save credential files as UTF-8 without a byte-order mark; the gateway does not strip it. A padded MTN country (`" cm"`) keeps working as before: the profile now
  carries the same stripped country its provider id already used. `provider-api` is unchanged.

### Security

- **No object that holds a credential prints it.** The bound configuration, the adapter
  profile, the bearer token, a merchant's webhook signing secret and a newly issued API key
  each print a constant marker in place of every credential, so a log line, an error message
  quoting a bound object, or a failing test assertion cannot print one. No code path in the
  gateway printed one before this change, apart from the two provisioning commands, which
  show a newly issued API key or webhook secret once because that is their job; nothing needs
  rotating. The masking closes the paths this code does not control. A test per record fails
  when a component is added without being classified.
- **A leaked M-Pesa credential supplied as a file can be replaced without a restart**, which
  makes replacing it quicker. The passkey has no observed revocation point, so replacing it is
  the only remedy there is (`docs/security-notes.md` §1). A botched rotation does not stop
  payments and does not go unseen: the last valid credentials stay in use, and a gauge reports
  it until the file is fixed. A Kubernetes Secret mounted by the chart is detected when it
  changes, measured on a cluster up to the operator's side; that Safaricom accepts the new
  passkey is not measured. The cost is that the last valid credentials
  stay in the process's memory for as long as it runs, where a heap dump shows them.
- **In Kubernetes, the default credentials directory would also import the pod's
  service-account token; the chart imports a directory of its own.** This release adds an
  import of `/run/secrets` (`NKAP_SECRETS_DIR` moves it). No released version imports any
  directory, so no earlier release is affected. *Measured:* in the gateway's image `/var/run` is
  a link to `/run`, and the import reads a directory's nested files. Given a directory laid out
  like Kubernetes' service-account volume, it produces the properties
  `kubernetes.io.serviceaccount.token`, `.ca.crt` and `.namespace`. *Not measured:* that a pod
  mounts the token there. That is where Kubernetes documents it,
  `/var/run/secrets/kubernetes.io/serviceaccount`, but no cluster was run for this. So a
  Kubernetes deployment of this release that keeps the default directory would have its
  service-account token read into the gateway's configuration as properties. The Helm chart
  sets `NKAP_SECRETS_DIR` to `/etc/nkap/credentials`, and CI fails if it is ever at or under
  `/run` or `/var/run`. A deployment by other means should set `NKAP_SECRETS_DIR` to a
  directory of its own.

## [2.0.0] - 2026-09-21

### Breaking

- **`SubmitResult` gains a fourth member, `NotAttempted` — an exhaustive `switch` over it
  stops compiling.** `provider-api` is published at `1.0.0`, so widening a sealed interface
  now takes a major release: this is Nkap **2.0.0**. Nothing about implementing an adapter
  changes — an adapter that returns `Acknowledged` or `Rejected` keeps compiling and running
  exactly as before, untouched. The only exhaustive `switch` today is
  `PaymentService.applyOutcome`, in this repository; a downstream project with its own
  exhaustive `switch` over `SubmitResult` needs a new case for `NotAttempted`, the same way
  this repository's own did.
- **A `payment_transition` row can no longer claim an operator spoke when none did**
  (ADR 0013). Two adapter-independent guards used to be indistinguishable, once persisted,
  from a genuine operator answer: an intent for an operation the adapter does not declare,
  and a currency the adapter's configured profile does not settle in. Both are now
  `CREATED → FAILED`, cause `GATEWAY` — a new value `PaymentTransition.Cause` and the
  payment-history `cause` field in `docs/openapi.yaml` can carry — recorded without ever
  calling the operator, instead of the `UNKNOWN`/`SUBMIT_RESPONSE` row either produced
  before, which then sent the reconciler chasing a reference the operator was never told
  about. Both MTN adapters keep working with no change from an integrator's side; the
  currency guard now returns `SubmitResult.NotAttempted` instead of throwing
  `IllegalArgumentException`, which only matters to a caller of `ProviderAdapter.submit`
  directly, not to `PaymentService`.
- **`ProviderAdapter` gains a new abstract method, `resolves()`** (ADR 0014): every adapter
  must now declare, as a `Set<Resolution>`, how a payment whose submission never answered can
  be resolved without a human — `QUERY`, `CALLBACK`, or both. There is no default
  implementation, on purpose — a default of `QUERY` would let an adapter that cannot query
  claim it can by declaring nothing — so an existing adapter does not compile until it adds
  one. Both MTN adapters now declare `QUERY` and `CALLBACK`.

### Added

- **A payment webhook's body gains a `cause` field** (issue #177), carrying what attributed
  the transition the event announces — `SUBMIT_RESPONSE`, `QUERY`, `CALLBACK`, `RECONCILER`
  or the new `GATEWAY` (ADR 0013) — the same values `GET /payments/{reference}`'s
  `history[].cause` already carries. Present on every event type, not only a failure: what a
  receiver actually needs it for is telling a `*.failed` event the operator refused
  (`SUBMIT_RESPONSE`/`QUERY`) from one this gateway refused itself without ever asking the
  operator (`GATEWAY`) — `providerCode` does not separate the two, since it is often `""` for
  an operator refusal too. Adding a field is not a breaking change — only renaming or
  removing one is, per `PaymentEventPayload`'s own javadoc — so a receiver written against
  the old shape keeps working unchanged and simply ignores it; a receiver that wants the
  distinction stops needing a second `GET` to get it. See `docs/webhooks.md`.
- `nkap-simulator` reads a scenario declaration from a file at startup: mount one at
  `/etc/nkap/scenario.json` (`nkap.scenario.file`) and the simulator comes up already
  misbehaving, with no `curl` first. Read once, applied exactly as if it had been `POST`ed
  to `/_nkap/scenarios` — the same document, the same validation — and never watched
  afterwards: it is not a second source of truth, `GET /_nkap/scenarios` keeps answering
  what is active and a later `POST` replaces what the file established the same way it
  replaces an earlier `POST` (ADR 0002's amendment). No file at the path is not an error;
  a file that does not parse as that document fails the simulator at startup, naming the
  path and what was wrong with it (issue #99).

### Changed

- **The MTN Collections adapter no longer treats every `409` on `requesttopay` as
  "already submitted."** It now reads the error body's `code` the same way a `400` already
  does, and only acknowledges when that code is `RESOURCE_ALREADY_EXIST` — a previous
  attempt genuinely reached MTN. Any other `409`, including one whose body cannot be read
  at all, now falls through to `ProviderUnavailableException`, exactly like any other
  unrecognised status: the payment is `202 UNKNOWN` and the reconciler chases it, rather
  than being silently recorded as submitted on a guess. This was deliberately loose until
  the simulator could return MTN-shaped error bodies (issue #26); it now can, so the
  looseness is gone (issue #28).

### Fixed

- **`nkap-simulator` no longer answers a Collections reference on the Disbursements path,
  or the mirror.** It used to keep one reference space across both MTN products, so a
  reference submitted on `/collection/v1_0/requesttopay` would also answer on
  `/disbursement/v1_0/transfer/{reference}` — and the reverse — exactly as if it were the
  same payment under both. Each product now has its own reference space: a status query for
  the wrong product gets the same answer as a reference that was never submitted at all,
  `404`. This is what makes a conformance rule about product routing possible to write at
  all — against the old, shared reference space, that rule would have passed whether or not
  an adapter actually routed correctly (issue #69).

## [1.2.0] - 2026-09-20

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
- `PaymentResponse.escalatedAt` and `.unresolvedSince`: a caller can now tell a payment
  nobody has looked at apart from one this gateway is actively chasing, and both apart from
  one it gave up chasing automatically and handed to a human — all three used to be
  byte-identical (issue #113). Both are RFC3339 timestamps, `""` when they do not apply,
  following the convention `refundOf` and `providerTransactionId` already use; neither is a
  new `PaymentState` member, since escalation describes what this gateway did about the
  operator's silence, not what the operator said. `reconcile_attempts` deliberately does not
  reach the response: it describes this gateway's own backoff policy, not the payment, and
  changing the reconciler's schedule would change the number for an identical payment.
- `GET /actuator/escalatedPayments`: the operator half of issue #113, closing it. Exposed by
  name on the management port, never on the API port — the audience is the operator across
  every merchant, not a merchant holding an API key. Calls `PaymentRepository.findEscalated`,
  which has existed since the reconciler shipped and which nothing in production ever called.
  Returns `reference`, `provider`, `merchantId`, `state`, `escalatedAt`, `unresolvedSince` and
  `reconcileAttempts` — this is `reconcileAttempts`'s home, deliberately left off the caller
  response above — and deliberately excludes `counterpartyMsisdn`, `amountMinorUnits`,
  `currency`, `payerMessage` and `payeeNote`: the first row-level data this port carries, and
  adding customer PII or per-payment business volume to an unauthenticated port would
  contradict the reasoning `application.yml` already gives for keeping it internal. Bounded to
  100 rows, enforced in the query itself so a rising backlog costs one read, not one avoided
  load per reference beyond the cap; `truncated` in the response says when that happened.

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

[Unreleased]: https://github.com/deval123/nkap/compare/v2.0.0...HEAD
[2.0.0]: https://github.com/deval123/nkap/compare/v1.2.0...v2.0.0
[1.2.0]: https://github.com/deval123/nkap/compare/v1.1.0...v1.2.0
[1.1.0]: https://github.com/deval123/nkap/compare/v1.0.0...v1.1.0
[1.0.0]: https://github.com/deval123/nkap/releases/tag/v1.0.0
