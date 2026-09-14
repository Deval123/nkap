# ADR 0010 — A refund is a disbursement caused by a collection, not a ledger reversal

- **Status:** accepted
- **Date:** 2026-09-13

## Context

Issue #84 is the last unbuilt mechanism the roadmap names before packaging and
documentation: §4's *"Refunds as reversing entries — never as an edit."*

That sentence contains a trap. "Reversing entries" reads, at a glance, like an invitation to
[`LedgerEntry.reversalOf`](../../core/src/main/java/dev/nkap/core/ledger/LedgerEntry.java) —
the method that already exists, already flips every posting's sign, and already produces an
entry that sums to zero against the original. Using it here would have been a real defect,
not a style choice.

`reversalOf` exists for a **mistake**: it writes *"Reversal of `<id>`: `<reason>`"*, and what
it means is *we recorded something that did not happen*. A refund is the opposite claim. The
collection happened. The merchant was paid. Now a second, real movement of money sends part
or all of it back. Recording that as a reversal of the collection would erase the only
evidence that the merchant was ever paid, and leave a refunded payment indistinguishable
from one that was never collected — in the ledger, in statement reconciliation, and in any
dispute. "Reversing entries" in the roadmap line means **the postings run the other way**: a
new entry, not the correction of an old one.

**The consequence that decides the rest of the design:** MTN has no refund endpoint.
Refunding a collection means transferring money back to the payer — a disbursement, over the
same API, with the same failure modes. A refund can time out. It can go `UNKNOWN`. It has to
be queried, chased by the reconciler, escalated when its window is spent, and it must never
be recorded `FAILED` for want of an answer. That is the core invariant this whole project
was built to hold, and it already exists, fully built and tested, for every other payment.
Any design where creating a refund writes a ledger entry before the operator has confirmed
the transfer is wrong in exactly the way this repository exists to prevent.

## Decision

**A refund is a `payment` row with `operation = DISBURSE` and a new column,
`refund_of uuid REFERENCES payment (reference)`, naming the `SUCCEEDED` collection it sends
money back for.** It is created, submitted, settled, reconciled, escalated and notified
through the exact same code `PaymentService`, `SettlementService`, `Reconciler`,
`OutboxNotifier` and the conformance kit already run for any disbursement. A refund is a
disbursement that knows why it exists, and that is the whole shape.

**Not a third `Capability.Operation`.** `provider-api` declares exactly `COLLECT` and
`DISBURSE`; a refund submits to the operator no differently from any other transfer, so a
third member would widen the adapter contract and the conformance kit for a distinction no
adapter needs to act on. `refund_of` carries the distinction instead — a column on the
gateway's own record, not a fact any operator is asked about.

**The ledger entry mirrors the collection's, in the collection's currency: credit the
provider float, debit the merchant payable** — the same shape `SettlementService.settle`
already writes for any disbursement (ADR 0007), because a refund's money leaves the float
and the merchant is owed that much less. Its id is `refund:<refund's own reference>`, its
own prefix rather than inheriting `disbursement:`, so a re-settle is refused by the ledger's
own duplicate rule the same way, and so a reader — or a metric reading entry ids — can tell
"paid out to a payee" apart from "paid back to one" without a join through `refund_of`. The
description names the original collection's reference, so the two are findable from each
other without a join through the application. **The operator's fee on the original
collection does not come back**: the merchant refunds the gross and eats the fee, the same
absence of an invented fee reversal ADR 0006 already decided; a fee on the transfer itself
arrives from the statement as its own entry, exactly as any operator fee does.

**Rules that decide whether a refund is even created, in order of how much they cost when
wrong:**

1. **Only a `SUCCEEDED` collection can be refunded.** `PENDING`, `FAILED`, `EXPIRED` and —
   deliberately, not just the terminal ones — `UNKNOWN` are all refused. Whether the payer's
   money was ever taken is not known for an `UNKNOWN` collection, and sending money back on
   that guess is a real, unrecoverable loss: the exact failure `UNKNOWN` exists to prevent.
2. **The destination is always the original collection's `counterparty_msisdn`, never one
   the caller supplies.** A refund endpoint that accepted a destination would be a
   funds-transfer API to an arbitrary phone, authenticated by a merchant API key — and keys
   are stored hashed precisely because they are assumed to leak one day. `RefundRequest`
   still declares a `counterpartyMsisdn` field, bindable, so that a caller who supplies one
   is refused with a `400` naming why, rather than have the field silently ignored and learn
   that it works. See `docs/positioning.md`.
3. **A refund never refunds a disbursement.** Sending money back to someone Nkap already
   paid is a new collection, with a different consent story, not a refund.
4. **Partial refunds are allowed; their total can never exceed the original — enforced by a
   database `CHECK`, not only an application check.** `payment.refunded_minor`, incremented
   under the original's own row lock (`SELECT … FOR UPDATE`, the same lock the settlement
   path already takes) the moment a refund is created, with
   `CHECK (refunded_minor >= 0 AND refunded_minor <= amount_minor)` behind it. Two concurrent
   requests each passing an application-level check and nothing else is exactly how a
   merchant gets refunded twice; the row lock plus the `CHECK` is the same defence-in-depth
   pattern `nkap_ledger_entry_balances` (V1) and `payment_amount_positive` already apply. A
   sum-over-rows deferred trigger was the other shape weighed — see *Alternatives rejected*.
5. **An in-flight refund reserves its amount from the moment it is created, not only once it
   settles.** What counts against the cap is every refund `SUCCEEDED` *or not yet terminal* —
   `UNKNOWN` included. If only `SUCCEEDED` counted, two refunds for half the remaining amount
   could both pass the check while both are in flight, and both then succeed. The reservation
   is released only if that refund ends `FAILED` or `EXPIRED`; an escalated refund keeps
   holding its reservation forever, because un-reserving it on a guess would let the same
   money leave twice if it later turns out to have gone through after all.

## Consequences

- **No new state machine, no new settlement path, no new reconciler path.** `refund_of` and
  `refunded_minor` are the entire schema change beyond what disbursements already needed.
  `RefundService` adds exactly one thing neither `PaymentService` nor `SettlementService`
  had: the reservation check-and-increment against the original, under its row lock, before
  a refund's `CREATED` row is ever persisted or the operator is ever asked. Submitting to the
  operator and applying whatever it answers is not duplicated — it is
  `PaymentService.submit`, extracted from `createAndSubmit` for exactly this reuse, called
  for a payment `RefundService` persisted itself.
- **A refund's terminal outcome is its own outbox event type** — `refund.succeeded`,
  `refund.failed`, `refund.expired` — rather than `payment.succeeded` et al., carrying the
  original's reference as `refundOf`. A merchant must be able to tell "your refund went
  through" from "your disbursement went through" without inspecting a field that is empty on
  every event that is not a refund's.
- **A refund is polled and replayed exactly like any other payment**:
  `GET /payments/{reference}` and idempotent replay of
  `POST /payments/{reference}/refunds` behave identically to `POST /payments`. There is no
  separate refund read model.
- `docs/positioning.md` gets one more paragraph under what the gateway does and does not
  protect: the destination rule above.

## Alternatives rejected

**A separate `refund` table with its own lifecycle.** The argument for it is real: a refund
is conceptually not a disbursement, and `refund_of` is `NULL` for every row that is not one.
The argument against is decisive here: a second table means a second state machine, a second
reconciler path and a second settlement write, and this repository has twice already removed
exactly that kind of duplication (the disbursements slice reusing the collection path, ADR
0007; the multi-country slice reusing one adapter shape per installation rather than a
second routing mechanism). The hard parts — timeout handling, escalation, idempotency,
outbox delivery — are already built and already tested once; a refund needing none of that
built a second time is the entire reason to prefer this shape.

**A deferred constraint trigger summing refunds per collection, the same shape
`nkap_ledger_entry_balances` uses.** Considered for rule 4 and set aside: that trigger exists
because a ledger entry's balance genuinely spans several rows written independently (each
posting, inserted one at a time). A refund's cap is a single-row invariant — one running
total on the collection's own row — so a plain `CHECK` on `refunded_minor`, maintained by the
application under the row's own lock, says the same thing with no deferred trigger and no
sum-over-rows query on every write.

**A fee reversal on the refund.** MTN charges no fee for a refund transfer itself in any
observation to date, and even if it did, ADR 0006 already decided operator fees are not
invented from the payment path — they arrive from the statement, as their own entry, however
the money moved. Refunding does not change what is owed for the *original* collection's fee:
the merchant paid it once, refunds the gross, and eats it, the same as any merchant-absorbed
cost that turns out to have been wasted.
