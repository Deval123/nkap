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
Collection of 5 000 XAF for merchant acme, operator fee 100, platform fee 50

  provider:mtn:float:XAF          DR  5 000
  merchant:acme:payable:XAF       CR  4 850
  fees:mtn:XAF                    CR    100
  fees:platform:XAF               CR     50
                                  ------------
                                      0
```

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

## Build

Requires JDK 21 and Maven 3.9+.

```bash
mvn test
```

## Roadmap

**v0.1 — the core is correct.** Double-entry ledger with its invariants enforced in the
database, state machine, idempotency on both fronts, MTN simulator, MTN collection
adapter, conformance kit. One currency, one operator, no UI.

**v0.2 — the system stands on its own.** Disbursements, reconciliation by statement
import with a discrepancy report, signed and replayable webhooks, Prometheus metrics
(including the suspense account balance), refunds as reversing entries.

**v0.3 — the project outgrows its author.** Second and third operators, ideally
contributed by other people through the conformance kit. Multi-currency with position
accounts, generated client SDKs, a read-only operations console.

## Contributing

Start with [CONTRIBUTING.md](CONTRIBUTING.md). The most accessible way in is a simulator
scenario: one failure mode per pull request, no operator account required.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

The explicit patent grant is deliberate: it matters to the companies who will evaluate
this for financial workloads.
