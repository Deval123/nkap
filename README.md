# Nkap

[![build](https://github.com/Deval123/nkap/actions/workflows/build.yml/badge.svg)](https://github.com/Deval123/nkap/actions/workflows/build.yml)

**An open mobile money gateway with a real double-entry ledger.**

Nkap sits between your application and the mobile money operators — MTN MoMo first,
others through the same contract — and gives you the three things every integration
rebuilds badly from scratch: an accounting record you can prove, a payment state machine
that never guesses, and a way to test the cases that actually break in production.

> **Status: pre-alpha.** The core is implemented and tested. Nothing is deployable yet.
> See the [roadmap](#roadmap).

---

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

A payment in `UNKNOWN` is re-queried by the reconciler on an exponential backoff
(`nkap.reconciler.*`). When the window is spent it is **escalated** — flagged for a human,
still `UNKNOWN`, never `FAILED` — and logged once at WARN. Escalated payments awaiting a
human are `PaymentRepository.findEscalated()`, or:

```sql
SELECT reference, merchant_id, amount_minor, currency, reconcile_attempts, escalated_at
FROM payment WHERE escalated_at IS NOT NULL AND state = 'UNKNOWN' ORDER BY escalated_at;
```

## Modules

| Module | What lives there |
| --- | --- |
| `core` | Ledger, state machine, idempotency. Pure domain — no web, no Spring, no database. |
| `provider-api` | The `ProviderAdapter` contract an operator integration implements. |
| `provider-mtn` | The MTN MoMo adapter. |
| `conformance` | The test kit every adapter must pass to be merged. |
| `simulator` | A scriptable fake operator that misbehaves on command. |
| `server` | Spring Boot: REST, webhooks, outbox, schedulers. |

`core` has no dependencies on purpose. It makes the accounting invariants testable in
milliseconds, without a container or a database — which is what makes an outside
contribution to the ledger reviewable.

## Quick start

Requires Docker. No JDK.

```bash
git clone https://github.com/Deval123/nkap
cd nkap
docker compose up --build -d      # builds the gateway and the simulator from source
./examples/demo.sh
```

`examples/demo.sh` puts one payment through the failure this project exists for, and
asserts every step:

1. the operator is scripted to accept the submission and then go **silent**;
2. `POST /payments` returns **202** and the payment is `UNKNOWN` — not `FAILED`, because
   nothing answered and the gateway does not guess;
3. `GET /payments/{reference}` confirms `UNKNOWN`, and the ledger has **no entry**;
4. the reconciler re-queries the operator on a demo-fast cadence — the first re-query still
   fails, the next succeeds — or the late callback gets there first;
5. the payment resolves to `SUCCEEDED`: **one** ledger entry, **two** postings, summing to
   **zero**.

The network dropped at the worst possible moment and the accounting truth was not lost.
Nothing in the run is staged: it is the real gateway, the real reconciler, and a real
PostgreSQL enforcing the ledger's invariants as constraints — the zero-sum check that
prints at the end is the database's, not the script's.

```bash
docker compose down -v            # stop, and wipe the database
```

From a cold clone — no build cache, base images not yet pulled — `docker compose up
--build` takes about half a minute on a fast connection; most of the variable part is the
one-time download of the build's dependencies (~1000 artifacts) inside the image, so a
slow link makes the first run longer. Later runs reuse the layers. The demo itself
finishes in about five seconds. `compose.yaml` builds from source — a published image you
can pull without a checkout is a later step on the [roadmap](#roadmap).

## Build

Requires JDK 21 and Maven 3.9+.

```bash
mvn test
```

## Roadmap

**v1.0.0 — MTN, end to end.** One operator, done properly: collections and disbursements
across MTN's countries, a ledger persisted in PostgreSQL with its invariants as database
constraints, reconciliation, signed webhooks, the conformance kit, and a `docker compose up`
that puts a payment through. The full definition of done is
[`docs/roadmap/v1.0.0-mtn-end-to-end.md`](docs/roadmap/v1.0.0-mtn-end-to-end.md) — nothing
off that list ships in 1.0.0.

**After 1.0.0 — the other operators.** Orange Money, Wave, M-Pesa, Airtel. This is the
contribution the architecture was built to accept: a new adapter is a self-contained module
that has to pass the conformance kit, which is what lets a maintainer merge an operator they
have no account with. The goal is every mobile money operator worth integrating.

Nkap runs on one container and one database. Kafka is an optional connector, not a
requirement — see [ADR 0003](docs/adr/0003-kafka-is-optional.md).

## Contributing

Start with [CONTRIBUTING.md](CONTRIBUTING.md). The most accessible way in is a simulator
scenario: one failure mode per pull request, no operator account required.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

The explicit patent grant is deliberate: it matters to the companies who will evaluate
this for financial workloads.
