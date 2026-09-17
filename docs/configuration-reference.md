# Configuration reference

Every `nkap.*` setting, its default, and — the column that makes this worth reading —
what a deployment actually experiences when it is wrong. Not "must be a duration": what
breaks, what silently misbehaves, or what fails loudly and where.

## Scope, and where the rest of the truth lives

This file documents two things: every `nkap.*` property, exhaustively, and the handful of
Spring settings a real deployment actually sets (the database connection, the two ports).
It does not attempt to document Spring Boot itself — `spring.jackson.*`,
`spring.flyway.locations`, `management.endpoints.web.exposure.include` and everything else
`application.yml` sets are Spring Boot's and Micrometer's own settings, and Spring Boot's
own reference documentation is the correct, current place to read about them. `docs/webhooks.md`
already documents the environment variables the compose files read for MTN credentials and
the database; this file is about `nkap.*` and the two ports, not a restatement of every
`${VAR:default}` in `application.yml`.

Every default below is the literal value `server/src/main/resources/application.yml` ships
with today. `ConfigurationReferenceTest` (in `server/src/test/java/dev/nkap/server/`) is
what keeps this table from drifting from the code — see **How this table is kept honest**
at the end of this file for exactly what it checks and what it does not.

## The reconciler (`nkap.reconciler.*`)

`ReconcilerProperties` binds this block. It periodically re-queries payments MTN has not
yet given a final answer for, and escalates ones that have been unresolved too long.

| Property | Default | What happens when it's wrong |
| --- | --- | --- |
| `nkap.reconciler.enabled` | `true` | Off disables the reconciler entirely: `UNKNOWN` payments are never re-queried and never escalated. They sit forever unless an inbound callback happens to resolve them. Only turn this off when the reconciler runs as a separate process, or in a test — leaving it off on the one instance that owns this workload is silent, not obviously an outage. |
| `nkap.reconciler.interval` | `30s` | Too short and every tick re-queries the same still-pending payments, turning the reconciler into a load generator against MTN's own quota for that account. Too long and a payment that could have resolved in seconds sits `UNKNOWN` for minutes longer than it had to. |
| `nkap.reconciler.batch-size` | `100` | Too small and a backlog built up during an outage drains slowly, one small batch at a time, for longer than the outage itself lasted. Too large and a single pass issues that many operator queries at once — the same quota risk as too short an `interval`, concentrated into one tick instead of spread across many. |
| `nkap.reconciler.backoff-base` | `1m` | Too short re-queries a payment again before MTN could plausibly have a different answer, wasting a call on a payer who has simply not approved yet. Too long delays the first useful re-check of a payment that may already be resolved. |
| `nkap.reconciler.backoff-max` | `1h` | Must not be set below `backoff-base` — the record's own constructor refuses that combination at startup, so this particular mistake at least fails loudly rather than silently. Set too low (but still ≥ `backoff-base`), it caps the growing delay so early that it behaves like a short fixed interval, defeating the reason exponential backoff exists at all. |
| `nkap.reconciler.window` | `24h` | Too short and payments escalate to a human that would have resolved on their own — a slow payer, an operator catching up — training people to wave escalations away. Too long and a payment that is genuinely stuck sits unresolved past the point anyone could still usefully act on it with MTN. |
| `nkap.reconciler.stranded-refund-grace` | `2m` | Too short and the reconciler starts chasing a refund submit call that is still legitimately in flight (ordinary network latency, not a crash), duplicating work the normal flow already does. Too long and a refund whose submitting process actually crashed sits `CREATED`, unexamined, longer than necessary — though never worse than sitting there forever, which is what happens without this setting at all. |

## The outbox relay (`nkap.webhooks.*`)

`OutboxRelayProperties` binds this block. It delivers signed webhooks for terminal payment
and refund outcomes; `docs/webhooks.md` is the contract it delivers under.

| Property | Default | What happens when it's wrong |
| --- | --- | --- |
| `nkap.webhooks.enabled` | `true` | Off means the relay never runs: events queue up signed and ready, but nothing ever sends them — silently, with no error anywhere pointing at why a merchant is receiving no webhooks at all. Only turn this off when the relay runs as its own process, or in a test. |
| `nkap.webhooks.interval` | `10s` | Too short turns the relay into a load generator against receivers that are simply a little slow, not broken. Too long and a merchant waiting on a webhook waits longer than it had to after the event was already ready to send. |
| `nkap.webhooks.batch-size` | `100` | Too small and a backlog built up during a receiver outage drains slowly. Too large and one pass opens that many connections to receivers at once, which can overwhelm a receiver that has just come back up, undoing the point of backing off in the first place. |
| `nkap.webhooks.backoff-base` | `30s` | Too short retries a receiver's outage too eagerly, compounding whatever is already wrong on their end. Too long delays delivery to a receiver that in fact recovered quickly. |
| `nkap.webhooks.backoff-max` | `30m` | Same constraint and the same failure mode as the reconciler's `backoff-max`: it must not be below `backoff-base` (enforced at startup), and set too low it collapses growing backoff into a short fixed interval. |
| `nkap.webhooks.max-attempts` | `10` | Too low dead-letters an event during a receiver's ordinary few-minute blip, forcing a manual replay for something that would have delivered on its own. Too high delays how long a genuinely dead receiver's events keep retrying before `nkap_outbox_dead_lettered` (`docs/prometheus-alerts.yml`) actually fires — the alert that is how a dead-lettered event gets noticed in the first place. |
| `nkap.webhooks.request-timeout` | `5s` | Too short treats a slow-but-working receiver as failed, retrying unnecessarily and doubling the load on it for no reason. Too long lets one slow receiver occupy a relay pass for a long time, delaying every other event queued behind it in that same batch. |
| `nkap.webhooks.signature-tolerance` | `5m` | The gateway itself never reads this value back — it only signs, and only documents this number for whoever implements the receiving side (`docs/webhooks.md`'s verification recipe). A value here that does not match what `docs/webhooks.md` tells integrators to trust is a pure documentation-versus-reality gap: an integrator implementing the freshness check exactly as documented would reject genuinely fresh retries, or accept ones that are stale by Nkap's own standard, purely because the number they were told differs from the one actually configured. |
| `nkap.webhooks.allow-insecure-endpoint-url` | `false` | `true` lets an operator provision a webhook endpoint over plain `http`. The signature still protects the body's integrity, but the amount, the reference and the merchant become readable in clear text to anything on the path — acceptable for a local demo or a TLS-less test, never for a real deployment. |

## The default provider (`nkap.provider.default`)

Not part of a `@ConfigurationProperties` record — read directly with `@Value` by
`BalanceController`, `AccountHolderController` and `StatementImportRunner`, the three
consumers of a country-agnostic default.

| Property | Default | What happens when it's wrong |
| --- | --- | --- |
| `nkap.provider.default` | `mtn-cm` | `GET /balance` and `GET /account-holders/{msisdn}` are not per-country routes (issue #82); this is the installation they route to. If it names an installation that is not actually configured (its country is blank, see below), both routes fail with no adapter found for it. If it names one installation while another is also configured, calls silently land on the one this setting names, not the one a caller might assume from context — nothing checks that this points at the installation an operator actually intended to be the default. |

## The public callback base URL (`nkap.public-base-url`)

`PublicBaseUrl` binds this one, directly with `@Value`, and fills
`PaymentIntent.providerOptions()` for every real submission (`PaymentController`,
`RefundService`) with the URL both MTN adapters send as `X-Callback-Url` — the one thing that
map carries today (issue #116). Not part of `nkap.provider.mtn.*`: it names this deployment's
own reachable host, not an installation's endpoint at MTN, and it is the same value for every
installation and every future provider — `<public-base-url>/callbacks/<providerId>`, the path
`docs/openapi.yaml` defines, composed by Nkap rather than spelled out per deployment.

Setting this property is what exposes `POST /callbacks/{providerId}` — unauthenticated by
design — to the public internet at a real, guessable hostname. `docs/security-notes.md` §5
already records what that costs in practice (a hostname stood up for testing was scanned
within the hour); the design withstands it, since a callback settles nothing by itself.

| Property | Default | What happens when it's wrong |
| --- | --- | --- |
| `nkap.public-base-url` | *(blank; set per deployment)* | Blank (the default) means `providerOptionsFor` returns an empty map, no `X-Callback-Url` is ever sent, and MTN never calls this deployment back at all — not an error, since a payment still resolves through the reconciler, only slower. Set but not an absolute URL (missing scheme or host) fails the application at startup, the same way a malformed MTN installation `base-url` does. Set to a real, reachable URL but naming a host MTN's `providerCallbackHost` was not given at API-user creation, every submission fails with `INVALID_CALLBACK_URL_HOST` (`docs/providers/mtn.md`) — a payment failure caused entirely by a mismatch between this property and an operator-side allow-list nothing here can see. Set to a host that is reachable but not the one actually recorded with MTN, callbacks are silently never delivered, indistinguishable from leaving it unset except that a submission now also carries a header. |

## MTN installations (`nkap.provider.mtn.installations[].*`)

`MtnProperties` binds this block: a list, one entry per country (issue #82), each becoming
its own `ProviderAdapter` with its own `ProviderId` (`mtn-<country>`). A slot whose
`country` is blank is not an installation at all — `MtnConfiguration` skips it — the same
way an unset `disbursement.*` block is not a configured product. Every other field in a
*configured* slot (non-blank `country`) is validated at startup by `MtnProfile`'s own
constructor, which is why several rows below say "fails at startup": a slot that claims a
country but is missing a credential is caught before the first payment, not during it.

| Property | Default | What happens when it's wrong |
| --- | --- | --- |
| `nkap.provider.mtn.installations[].base-url` | *(blank; set per deployment)* | Left blank on a configured installation (non-blank `country`), the application refuses to start — `MtnProfile` requires an absolute URL. Set but pointing at the wrong host, the application starts fine and every real call to MTN for that installation fails at the network layer; under this project's timeout rule, that failure lands as `UNKNOWN`, not a hard failure, so the practical effect is real payments stuck rather than an obvious error. |
| `nkap.provider.mtn.installations[].target-environment` | *(blank; set per deployment)* | Blank on a configured installation fails at startup, the same as `base-url`. Set but wrong, MTN's own API answers every call for that installation with an authorization error — the failure surfaces at MTN's side, not Nkap's. |
| `nkap.provider.mtn.installations[].subscription-key` | *(blank; set per deployment)* | Blank fails at startup. Wrong, MTN rejects every call for that installation with `401`. |
| `nkap.provider.mtn.installations[].api-user` | *(blank; set per deployment)* | Blank fails at startup. Wrong, MTN's OAuth token exchange fails, so no call for that installation ever gets past acquiring a token. |
| `nkap.provider.mtn.installations[].api-key` | *(blank; set per deployment)* | Same as `api-user`: blank fails at startup, wrong fails the token exchange. |
| `nkap.provider.mtn.installations[].currency` | *(installation-specific; e.g. `XAF` for the Cameroon slot, `GHS` for the Ghana slot)* | This project enforces one currency per ledger entry and never converts (the four rules that do not bend, `CLAUDE.md`). A wrong-but-valid currency code here is not rejected — every payment through that installation is booked and settled in the wrong currency as a plain fact in the ledger, not an error anyone is warned about. |
| `nkap.provider.mtn.installations[].country` | *(installation-specific; e.g. `cm`, blank for the second slot)* | Blank: the installation is skipped entirely and `POST /payments` refuses that country with a `400` — a configuration mistake presenting as a client error. Non-blank but colliding with another installation's country (both resolving to the same `ProviderId`), the application refuses to start at all, naming the clash. |
| `nkap.provider.mtn.installations[].request-timeout` | `20s` | Too short treats a live but slightly slow MTN response as absent before it returns; this project's timeout rule turns that into `UNKNOWN`, not a failure, so nothing is corrupted — but it manufactures reconciler work for payments that would have resolved with a slightly longer wait. Too long lets a genuinely hung call to MTN hold on to whatever issued it for that much longer before Nkap gives up on it. |
| `nkap.provider.mtn.installations[].disbursement.subscription-key` | *(blank; set per deployment)* | These three fields (and the two below) are read together: `application.yml` defaults all three blank, which means the installation offers no `DISBURSE` capability at all — refunds and disbursements are simply unavailable for it, not an error. Setting only one or two of the three is exactly as unconfigured as setting none; nothing points out the missing third one. |
| `nkap.provider.mtn.installations[].disbursement.api-user` | *(blank; set per deployment)* | See `disbursement.subscription-key` — all three are checked together. |
| `nkap.provider.mtn.installations[].disbursement.api-key` | *(blank; set per deployment)* | See `disbursement.subscription-key` — all three are checked together. |

## Spring settings a deployment actually sets

Everything else Spring Boot reads is Spring Boot's own documentation to consult. These
four are the ones every compose file in this repository actually sets, so they earn a row
here.

| Setting | Default | What happens when it's wrong |
| --- | --- | --- |
| `server.port` | `8080` | An occupied or invalid port fails the gateway at startup — it refuses to bind rather than silently serving on a different port. |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/nkap` (env: `NKAP_DB_URL`) | Unreachable, and the gateway fails at startup on its first connection attempt rather than serving traffic against no database. Reachable but pointed at the wrong database, the gateway starts and runs Flyway migrations against — and then reads and writes — whichever database this actually names, silently, with no indication it is not the one intended. |
| `spring.datasource.username` / `spring.datasource.password` | `nkap` / `nkap` (env: `NKAP_DB_USER` / `NKAP_DB_PASSWORD`) | Wrong credentials fail the gateway at startup the same way an unreachable URL does — PostgreSQL refuses the connection before Flyway or anything else runs. |
| `management.server.port` | `9464` | This is the port `application.yml`'s own comment already explains at length: publishing it — unlike the API's own port, which is meant to be public — hands anyone who can reach it payments-by-state, reconciler passes and escalations, operator latency, and the suspense balance in a real currency. No compose file in this repository maps it to the host; it must stay that way behind a proxy, on an internal network only. |

## How this table is kept honest

`ConfigurationReferenceTest` reads `ReconcilerProperties`, `OutboxRelayProperties` and
`MtnProperties` through reflection — walking every record component, including the nested
`installations[]` list and its own nested `disbursement` block — and asserts that set of
real property names matches this file's table exactly in both directions: a property with
no row here fails the test, and a row naming a property nothing in code reads fails it too.
It separately checks that this table's Default column agrees with `application.yml`'s own
literal value, for every `nkap.reconciler.*` and `nkap.webhooks.*` property, and for the one
MTN field with an unambiguous default across both installation slots
(`installations[].request-timeout`, `@DefaultValue("PT20S")` — the only source for it at all
in the second installation, which sets no `request-timeout` key of its own).

Two things it deliberately does not do, stated here rather than left to be discovered:

- **`nkap.reconciler.enabled`, `nkap.webhooks.enabled`, `nkap.webhooks.allow-insecure-endpoint-url`
  and `nkap.provider.default`** are not part of any `@ConfigurationProperties` record — the
  first two are read with `@ConditionalOnProperty`, the other two with `@Value`. There is no
  single class to reflect on for these the way there is for the three records above, so the
  test names these four by hand as a known, hard-coded set rather than discovering them. That
  is a real, narrower guarantee than the records get: today's four are proven documented, but
  a *fifth* setting read the same direct way tomorrow would not be caught by this test unless
  it is also added to that hard-coded set by hand.
- **Only `installations[].request-timeout`'s default is cross-checked.** The other MTN
  installation fields have no single meaningful default to check against: most are blank on
  purpose (real credentials are supplied per deployment, never committed), and the two
  installation slots `application.yml` actually declares do not even agree with each other
  (the first slot defaults `country` to `cm`, the second to blank) — there is no one literal
  value for the table to be checked against for those fields.
