# Nkap

[![build](https://github.com/Deval123/nkap/actions/workflows/build.yml/badge.svg)](https://github.com/Deval123/nkap/actions/workflows/build.yml)

**An open mobile money gateway with a real double-entry ledger.**

Nkap sits between your application and the mobile money operators — MTN MoMo first,
others through the same contract — and gives you the three things every integration
rebuilds badly from scratch: an accounting record you can prove, a payment state machine
that never guesses, and a way to test the cases that actually break in production.

> **Status: released and runnable.** Every tagged release publishes public images for
> `linux/amd64` and `linux/arm64`, no login needed to pull — see
> [Run it without cloning](#run-it-without-cloning). **It has never handled real money** —
> not in any country, by anyone, including its author: every observation this project has
> made against MTN and M-Pesa was against a sandbox. Key revocation is a host-side command
> that marks a key rather than deleting it, and takes effect on the key's very next request;
> [`docs/security-notes.md`](docs/security-notes.md) holds the rest of what to know before
> you point this at anything real.

---

## The five-minute path

Three commands, from a cold clone, put one payment through the failure this project exists
for — the operator accepts the submission, then goes silent — and show the ledger settle it
anyway, without ever calling it a failure:

```bash
git clone https://github.com/Deval123/nkap
cd nkap
docker compose up --build -d && ./examples/demo.sh
```

What you should see, trimmed to the shape that matters — the full walkthrough is in
[Quick start](#quick-start-the-contributors-path) below:

```
▸ 2. POST /payments — the network drops before the operator replies
   ✓ HTTP 202 Accepted
   ✓ state is UNKNOWN — the outcome is genuinely not known, and the gateway does not guess

▸ 4. Waiting for the reconciler to re-query the operator and settle it
     state: UNKNOWN
     state: SUCCEEDED
   ✓ resolved to SUCCEEDED
   ✓ the transition into SUCCEEDED carries cause RECONCILER — the reconciler resolved it, no callback involved

▸ 5. The ledger entry that settlement wrote

     provider:mtn-cm:float:XAF             +5,000
     merchant:acme:payable:XAF             -5,000
                                       -----------
                                                 0
```

`UNKNOWN`, not `FAILED` — nothing answered, and the gateway does not guess. The reconciler,
not a callback, is what resolves it. And the ledger balances to zero, enforced by PostgreSQL
as a constraint on the table — not printed because the script says it should.

That is the whole argument this project makes, running, on your own machine, in about the
time it took to read this section.

## Why this exists

Mobile money is the dominant means of payment across much of sub-Saharan Africa, and
every team that integrates it hits the same four problems.

**The silent timeout.** The payment request goes out, the network drops before the reply
comes back. Did the money move? Without a reference persisted *before* the call, nothing
can ever query it again.

**The double charge.** The user taps "Pay" twice. Without an idempotency key carried all
the way through to the operator, they pay twice — and support finds out before you do.

**The invented balance.** The merchant's balance is an `UPDATE` on a column. No trace of
how it got there, no way to reconcile against the operator's statement, no way to correct
it without rewriting history.

**The untestable.** Operator sandboxes are unstable and never reproduce the ugly cases:
duplicate callbacks, late callbacks, callbacks for references you have never seen. So
nobody tests them, and they all arrive in production.

These are solved problems in Western payment systems. They are solved nowhere in a free,
reusable form adapted to mobile money APIs.

## What Nkap is — and is not

| Is | Is not |
| --- | --- |
| A unified collection and disbursement API | A licensed payment institution |
| An immutable double-entry ledger | A custodian of funds — money stays at the operator |
| An automatic reconciliation engine | A KYC or AML engine |
| A scriptable operator simulator for tests | A merchant dashboard |
| Self-hostable, with no SaaS dependency | A marketplace or a hosted service |

The right-hand column matters as much as the left. It protects the project legally, and
it protects the scope from feature requests.

## The three rules of the ledger

1. **Append only.** No `UPDATE`, no `DELETE` on entries. A mistake is corrected by a
   reversing entry, never by rewriting the record.
2. **Zero sum.** Every entry has at least two postings whose signed amounts sum to
   exactly zero. Enforced in the constructor, and in the database — a rule that lives
   only in Java is a rule a migration script walks straight through.
3. **Integers, in minor units.** Never a floating-point number. One currency per entry;
   FX is a separate entry through a position account.

A balance is never stored. It is the sum of an account's postings, so it can always be
explained by pointing at the entries that produced it.

```
A settled collection of 5 000 XAF for merchant acme — gross, two postings:

  provider:mtn:float:XAF          DR  5 000
  merchant:acme:payable:XAF       CR  5 000
                                  ------------
                                      0

The operator's fee is a second entry, written when the statement says what it was:

  fees:mtn:XAF                    DR    100
  provider:mtn:float:XAF          CR    100
                                  ------------
                                      0
```

The fee is not part of the settlement, and that is deliberate. A payment must be recordable
from what the payment itself carries; an entry that needed a fee the operator may never send
could not be written at all. The operator's statement is the authority on fees, and an entry
added a day later is not a correction — it is a second entry about a second real event,
which is what an append-only ledger is for. Nkap charges no fee of its own. See
[ADR 0006](docs/adr/0006-what-a-settled-collection-posts.md).

An entry itself says nothing about whether the payment behind it was ever real: `provider_id`
is derived from an installation's country, so a stack pointed at the simulator and one
pointed at MTN write the same value. `ledger_entry.reference` is how an auditor follows an
entry back to its payment, and `payment.provider_base_url` — the installation's own base URL,
recorded once at creation and never changed after — is what settles the question there
(issue #122). It began with the migration that added it; a payment recorded before that
carries none, deliberately, rather than a backfilled guess.

Where Nkap sits, and why it is not Mojaloop, not a ledger engine and not another
unified API: [docs/positioning.md](docs/positioning.md).

## The rule that shapes everything else

> **A timeout is not a failure.**

A call that did not answer moves to `UNKNOWN`, never to `FAILED`. `UNKNOWN` is not a
terminal state: the reconciler resolves it, or a human is paged. No code in this system
may conclude that a payment failed without the operator having said so explicitly.

```
CREATED → SUBMITTED → PENDING → SUCCEEDED   (terminal)
                   ↘         ↘  FAILED      (terminal)
                     UNKNOWN ↗  EXPIRED     (terminal)
                       ↑
              every timeout lands here
```

Every payment that has left `CREATED` and not reached a terminal state — `SUBMITTED`,
`PENDING`, `UNKNOWN` — is re-queried by the reconciler on an exponential backoff
(`nkap.reconciler.*`): an acknowledged submission that goes silent, or a `PENDING` the
payer never approves, is just as unresolved as a timeout. When the window (wall-clock time
since the payment became unresolved) is spent it is **escalated** — flagged for a human,
still non-terminal, never `FAILED` — and logged once at WARN. Escalated payments awaiting a
human are `PaymentRepository.findEscalated()`, or:

```sql
SELECT reference, merchant_id, amount_minor, currency, reconcile_attempts, escalated_at
FROM payment
WHERE escalated_at IS NOT NULL AND state IN ('SUBMITTED', 'PENDING', 'UNKNOWN')
ORDER BY escalated_at;
```

## Modules

| Module | What lives there |
| --- | --- |
| `core` | Ledger, state machine, idempotency. Pure domain — no web, no Spring, no database. |
| `provider-api` | The `ProviderAdapter` contract an operator integration implements. |
| `provider-mtn` | The MTN MoMo adapter. |
| `provider-mpesa` | The M-Pesa STK Push adapter. Certified by the kit; configurable in the server, never yet run against Safaricom. |
| `conformance` | The test kit every adapter must pass to be merged. |
| `test-support` | Test infrastructure several modules share: booting a simulator, checking a record's `toString()`. Used at test scope only. |
| `simulator-core` | What the fake operator does: scenarios, its memory of payments, callbacks, the `/_nkap` control plane. |
| `simulator-mtn` | How the fake operator says it the way MTN does: routes, bodies, statuses, authentication. |
| `simulator-mpesa` | How it says it the way Safaricom's M-Pesa STK Push does. Tested on its own, and assembled into a deployable by `simulator-mpesa-app`. |
| `simulator` | A scriptable fake operator that misbehaves on command — the application and image built from `simulator-core` and `simulator-mtn`. |
| `simulator-mpesa-app` | The same for M-Pesa — the application and image built from `simulator-core` and `simulator-mpesa`, on port 8082. |
| `server` | Spring Boot: REST, webhooks, outbox, schedulers. |

`core` has no dependencies on purpose. It makes the accounting invariants testable in
milliseconds, without a container or a database — which is what makes an outside
contribution to the ledger reviewable.

## Quick start (the contributor's path)

Requires Docker. No JDK. This builds Nkap from source, which is right for trying the code
before you change it — if you just want to run Nkap, see
[Run it without cloning](#run-it-without-cloning) below.

```bash
git clone https://github.com/Deval123/nkap
cd nkap
docker compose up --build -d      # builds the gateway and the simulator from source
./examples/demo.sh
```

`examples/demo.sh` puts one payment through the failure this project exists for, and
asserts every step. `POST /payments` is authenticated: `compose.yaml` provisions one
obviously-named demo API key before the gateway starts, and the script sends it as
`Authorization: Bearer`. The merchant is the key's, never a body field.

1. the operator is scripted to accept the submission and then go **silent**;
2. `POST /payments` returns **202** and the payment is `UNKNOWN` — not `FAILED`, because
   nothing answered and the gateway does not guess;
3. `GET /payments/{reference}` confirms `UNKNOWN`, and the ledger has **no entry**;
4. the reconciler re-queries the operator on a demo-fast cadence — the first re-query still
   fails, the next succeeds — with no callback in the scenario, so only the reconciler can
   resolve it;
5. the payment resolves to `SUCCEEDED`, its transition attributed to `RECONCILER`: **one**
   ledger entry, **two** postings, summing to **zero**.

```bash
docker compose down -v            # stop, and wipe the database
```

From a cold clone — no build cache, base images not yet pulled — `docker compose up
--build` takes about half a minute on a fast connection; most of the variable part is the
one-time download of the build's dependencies (~1000 artifacts) inside the image, so a
slow link makes the first run longer. Later runs reuse the layers. The demo itself
finishes in about five seconds. `compose.yaml` builds from source, on purpose — it is the
fastest way to see a change you just made in `server/src` take effect, without deciding
what to publish first.

## Run it without cloning

**You clone to contribute, you pull an image to use.** Every tagged release publishes
[`ghcr.io/deval123/nkap-gateway`](https://github.com/deval123/nkap/pkgs/container/nkap-gateway)
and
[`ghcr.io/deval123/nkap-simulator`](https://github.com/deval123/nkap/pkgs/container/nkap-simulator)
(MTN) —
public, no login needed to pull, `linux/amd64` and `linux/arm64` — tagged with the exact
version and with a moving `latest` that always points at the newest release, never at `main`.
`docker inspect ghcr.io/deval123/nkap-gateway:latest` names the exact commit and version it
was built from.

The M-Pesa simulator's image, `ghcr.io/deval123/nkap-simulator-mpesa`, has not been published
yet: it ships from the first release after 2.0.0. Until then, run it from a clone with the two
commands in [`simulator-mpesa-app/README.md`](simulator-mpesa-app/README.md#running-it).

This is for running Nkap for real, against your own MTN credentials — not for trying it. If
you have not run Nkap before, the [quick start](#quick-start-the-contributors-path) above is a
better first stop: it needs no MTN account, it is faster to get a payment moving through, and
it is the same gateway. Once you know what Nkap does and you are ready to point it at MTN,
come back here.

```bash
curl -fsSL -o nkap-standalone.compose.yaml \
  https://raw.githubusercontent.com/deval123/nkap/v2.0.0/nkap-standalone.compose.yaml
```

Pinned to a tag, not to `main`, so the file you get and the images it names are the same
release — substitute the release you actually want; see
[Releases](https://github.com/deval123/nkap/releases) for the list.

This file provisions no API key and starts nothing without one — deriving a key from
`compose.yaml`, the obvious shortcut, would put a fixed, well-known credential, published in
this public repository, in front of a real gateway. `key-init` below mints a real one instead,
the same way `--nkap.apikey.create` always does, and prints it once. It also changes none of
`application.yml`'s cadence: `compose.yaml`'s fast timeouts exist to make a demo watchable in
one sitting, and would hammer a real MTN account and burn the reconciler's escalation window
in minutes if carried here. And it never runs a simulator — an operator deploying Nkap for
real must never have one reachable from the same process that moves real money; see the file's
own comment for the rest of that reasoning.

```bash
export NKAP_VERSION=2.0.0                 # the release you downloaded the file for
export NKAP_DB_PASSWORD=$(openssl rand -hex 32)
export NKAP_MERCHANT_ID=your-merchant-id
docker compose -f nkap-standalone.compose.yaml up -d
docker compose -f nkap-standalone.compose.yaml logs key-init
```

Any variable left unset fails fast with a one-line message naming it, before any container
starts. The last command prints your API key — **once**; only its hash is ever stored, and
there is no command or route that reads it back. Save it now. Bringing the stack up again
after a `docker compose down` runs `key-init` again too, which mints and prints a *new* key
for the same merchant — the old one keeps working until you revoke it:

```bash
docker compose -f nkap-standalone.compose.yaml run --rm gateway \
  --nkap.apikey.revoke --nkap.apikey.id=<the old key's id>
```

so this is a second credential, not a replacement, until you deliberately revoke the first
one — see [`docs/security-notes.md`](docs/security-notes.md) for what revoking actually does
(it marks the row, and takes effect on the very next request). If you only want the one key,
leave the stack running rather than cycling it, or provision it once by hand instead (see
below) and remove `key-init` from the file.

Once it is up, fill in your real MTN credentials the same way — the file lists every variable
it reads, each defaulting to unconfigured rather than to a placeholder — and provision
additional keys, for additional merchants, the same command `compose.yaml`'s own comment
describes (no `--nkap.apikey.token`: that is what lets the gateway generate one instead of you
choosing one, which is the only way a fast, unsalted hash is safe to store):

```bash
docker compose -f nkap-standalone.compose.yaml run --rm gateway \
  --nkap.apikey.create --nkap.apikey.merchant=<id>
```

### Two compose files, one volume

If you have ever run `compose.yaml` and `nkap-standalone.compose.yaml` on the same machine,
in either order — which is the natural thing to do when you try the demo first and then run
Nkap for real — you may have hit this: both files resolved to the same Compose project, so
they shared one PostgreSQL volume. PostgreSQL applies `POSTGRES_PASSWORD` only the first time
it initialises an empty data directory, so whichever file touched the volume first kept its
password, and the other could never connect — `FATAL: password authentication failed for
user "nkap"`, forty lines into a Flyway stack trace inside `key-init`'s or `webhook-init`'s
logs, with `docker compose up` already returned and the gateway correctly left `Created`,
never started. Issue #123 fixed it: `compose.yaml` now names its own project (`nkap-demo`)
and the two can no longer collide; a `nkap_*` volume from before that change, if you have
one, is simply unused now — see `compose.yaml`'s own header for why leaving it behind is the
right call, and `CHANGELOG.md` for which release actually carries this fix.

If you are seeing that exact `FATAL` today, on either file, the non-destructive repair is to
reset the password the volume already has, not to erase the volume:

```bash
docker compose exec -T db psql -U nkap -d nkap -c "ALTER USER nkap PASSWORD 'nkap';"
```

`docker compose down -v` "fixes" it too, but it deletes the ledger and every payment's
history along with it — the accounting record this project exists to keep — to solve a
one-line password mismatch. Use it only if you genuinely want a clean slate.

## Running on Kubernetes

[`charts/nkap`](charts/nkap) is a Helm chart for the gateway, from the same published image —
`values.yaml` never accepts a credential value, not the database password, not an MTN
subscription key, api user or api key: every one is the *name* of a `Secret` you create
yourself, and rendering fails, naming what is missing, exactly the way
`nkap-standalone.compose.yaml`'s `${VAR:?message}` does. **Read
[`charts/nkap/README.md`](charts/nkap/README.md) before installing** — it answers three
questions a compose file never had to: where the first API key appears and who else can read
it, what runs the migrations with more than one replica, and whether more than one replica is
safe at all.

## The simulator on its own

The most numerous audience for this project is people integrating against MTN directly, who
will never run the gateway at all. If that is you, and you want to test the cases that
actually break in production — a timeout, a duplicate callback, a flapping status — the
simulator is the most useful single piece of Nkap, and it needs nothing else:

```bash
docker run -p 8081:8081 ghcr.io/deval123/nkap-simulator
```

Script it, then point whatever you are testing at `http://localhost:8081` in place of MTN's
own base URL:

```bash
curl -X POST http://localhost:8081/_nkap/scenarios \
  -H 'Content-Type: application/json' \
  -d '{
        "rules": [{
          "scenario": {
            "name": "times-out-then-succeeds",
            "onSubmit": { "outcome": "NO_RESPONSE" },
            "onQuery": [{ "status": "SUCCESSFUL" }]
          }
        }]
      }'
```

Or, when a scenario belongs beside the tests it is for and the control plane is a second step
you would rather not script, mount it and skip the `curl` entirely — the same document,
applied before the simulator's first request (issue #99):

```bash
docker run -p 8081:8081 -v ./scenario.json:/etc/nkap/scenario.json ghcr.io/deval123/nkap-simulator
```

**The simulator is not MTN.** [`docs/providers/mtn.md`](docs/providers/mtn.md) separates what
was actually observed against MTN's sandbox from what this project chose to model where MTN's
own behaviour is undocumented or untested; the simulator implements the second column, and a
mismatch between the two is a bug in the simulator or the doc, not in MTN. Read that file for
what it imitates before you trust an integration test that only ever ran against this.

**There is an M-Pesa simulator too**, on port 8082 so the two run side by side. Its image,
`ghcr.io/deval123/nkap-simulator-mpesa`, ships from the first release after 2.0.0, and pulling
it works for everyone only once a maintainer has made the package public by hand. Until then,
run it from a clone:

```bash
mvn -B -pl simulator-mpesa-app -am package -DskipTests
java -jar simulator-mpesa-app/target/nkap-simulator-mpesa-app-*-boot.jar
```

It plays Safaricom Daraja's STK Push, collections only, and is scripted through the same
`/_nkap` control plane. **Never give it real credentials, and never expose `/_nkap`:** it
reports the Consumer Key and Secret it was sent, in the clear, to anyone who can reach it.
[`simulator-mpesa-app/README.md`](simulator-mpesa-app/README.md) says what it plays and what
it does not.

**Orange Money is read, not implemented.** There is no Orange adapter in this repository, and
1.0.0 does not plan one. [`docs/providers/orange-money.md`](docs/providers/orange-money.md)
records what a review of `provider-api` against Orange's documented API found — including two
contract findings a redirect-style operator surfaces that MTN never could. Everything on that
page is *assumed*, and more weakly sourced than `mtn.md`: nobody on this project holds an
Orange developer account, and the page says so rather than presenting a guess as a fact.

## Integration guide and API reference

[`docs/integration-guide.md`](docs/integration-guide.md) is for making your own backend talk
to Nkap: getting a key, the first collection, what to do with `UNKNOWN`, receiving and
verifying a webhook, a refund and what its cap refuses, and the errors an integration
actually hits. [`docs/openapi.yaml`](docs/openapi.yaml) is the same API as a schema — every
path, method, status code and field. Both are checked against the running application on
every build (`OpenApiSpecIT`, `examples/run-integration-guide.sh`), not merely written once
and left to drift.

[`docs/configuration-reference.md`](docs/configuration-reference.md) covers every `nkap.*`
setting, its default, and what a deployment actually experiences when it is wrong — kept
honest against the code by `ConfigurationReferenceTest`.
[`docs/security-notes.md`](docs/security-notes.md) covers credential handling, webhook
verification, what Nkap does and does not protect, and what an operator has to do that Nkap
cannot.

[`CHANGELOG.md`](CHANGELOG.md) says what each tagged release actually is, what it
deliberately does not do, and — from `v1.0.0` onward — what stability means: which parts of
this project are a published contract and which are today's behaviour, not a promise.

## Build

Requires JDK 21 and Maven 3.9+.

```bash
mvn test
```

## Roadmap

**v1.0.0 shipped — MTN, end to end.** One operator, done properly: collections and
disbursements across MTN's countries, a ledger persisted in PostgreSQL with its invariants as
database constraints, reconciliation, signed webhooks, the conformance kit, and a
`docker compose up` that puts a payment through.
[`docs/roadmap/v1.0.0-mtn-end-to-end.md`](docs/roadmap/v1.0.0-mtn-end-to-end.md) was the full
definition of done — a historical record now, not a plan.

**After 1.0.0 — the other operators.** This is the contribution the architecture was built
to accept, and the goal is every mobile money operator worth integrating. A new operator is
two modules: an adapter, which has to pass the conformance kit, and a face on the simulator,
which is what the kit drives — the operator's routes, bodies, statuses and authentication,
over a neutral core every face shares. Together they are what lets a maintainer merge an
operator they have no account with. M-Pesa is the first to show it: `provider-mpesa` (STK
Push, Collections) passes every rule of the kit against `simulator-mpesa`, and was written
without a Safaricom account. Under [ADR 0014](docs/adr/0014-resolvable-not-queryable.md) its
lost-submission case is resolved by callback rather than by query, using the callback URL the
gateway composes per payment ([#194](https://github.com/Deval123/nkap/pull/194),
[#200](https://github.com/Deval123/nkap/pull/200)). A deployment can now be pointed at
Safaricom (`nkap.provider.mpesa.*`, one Kenya slot, which requires `nkap.public-base-url`),
but no payment has been made through it against Safaricom itself.

Nkap runs on one container and one database. Kafka is an optional connector, not a
requirement — see [ADR 0003](docs/adr/0003-kafka-is-optional.md).

## Contributing

Start with [CONTRIBUTING.md](CONTRIBUTING.md). The most accessible way in is a simulator
scenario: one failure mode per pull request, no operator account required.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

The explicit patent grant is deliberate: it matters to the companies who will evaluate
this for financial workloads.
