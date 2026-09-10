# ADR 0007 — What a settled disbursement posts to the ledger

- **Status:** accepted
- **Date:** 2026-09-10

## Context

[ADR 0006](0006-what-a-settled-collection-posts.md) decided what a settled **collection**
posts: gross, two postings, `DR provider:<op>:float / CR merchant:<id>:payable`, no fee at
settlement — the operator's fee arrives from the statement as its own entry and Nkap absorbs
it. Its title says *collection* and it stays true.

Issue #62 adds disbursements — money out — through the same machinery. A settled
disbursement also writes to the ledger, and what it writes has not been decided. It is the
mirror of a collection, and a mirror deserves to be written down rather than assumed.

## Decision

**A settled disbursement posts the gross amount, in two postings, in the payment's
currency, summing to zero:**

```
CR  provider:mtn:float:XAF        amount     (the float goes down — money left it)
DR  merchant:acme:payable:XAF     amount     (we owe the merchant that much less)
```

This is [ADR 0006](0006-what-a-settled-collection-posts.md)'s entry with both postings
flipped. Nothing else about it differs:

- **One entry, on `SUCCEEDED`, once.** The id is derived from the reference
  (`disbursement:<ref>`), so a re-settle is refused by the ledger's own duplicate rule, the
  same as a collection (`collection:<ref>`).
- **No fee at settlement.** ADR 0006's fee rule is unchanged and is not restated here: a
  disbursement fee, when the operator charges one, arrives from the statement as
  `DR fees:mtn:<CCY> / CR provider:mtn:float:<CCY>` and Nkap absorbs it. The statement
  importer needed no change to handle it — a fee is a fee whichever direction the settlement
  went.
- **`FAILED` and `EXPIRED` post nothing; `UNKNOWN` posts nothing.** A disbursement the
  operator refuses — including for lack of funds — is an ordinary `FAILED` with the
  operator's code, and the ledger stays untouched.

## What is not obvious

**A disbursement can fail for lack of funds at the operator, and that is an ordinary
`FAILED` from MTN — not something this gateway predicts, refuses in advance, or holds a
reserve against.** Nkap does not custody funds and does not model a float balance it could
check before submitting; `provider:mtn:float` is a ledger account reconciled against the
operator's statement, not a spendable balance. If a transfer is submitted and MTN answers
`NOT_ENOUGH_FUNDS`, the payment is `FAILED`, nothing is posted, and a human tops up the
operator account out of band. Predicting the failure would mean trusting a balance we
compute rather than the operator's answer — the same class of mistake as calling a timeout
a failure.

## Consequences

- **Settlement grew one branch.** `SettlementService.settle` now chooses the posting
  direction and the entry-id prefix from `payment.intent().operation()`. This is the one
  place that could not stay direction-agnostic: a ledger entry cannot be written without
  knowing which way the money moved, and that is the operation. The state machine,
  idempotency, `confirm`/`applyConfirmed`, the reconciler and the statement importer were
  untouched — see the pull request for #62, which reports that explicitly because a "no" was
  the result the slice was designed to test.
- **`merchant:<id>:payable` can go negative** for a merchant that has been paid out more
  than it has collected. That is correct — it means Nkap has advanced the merchant money —
  and the ledger already allows any balance; a balance is the sum of postings, not a stored
  non-negative number.
- **Multi-currency needs nothing special**, the same as ADR 0006: both postings are in the
  payment's currency and `LedgerEntry` refuses to mix two.

## Alternatives rejected

**Hold a reserve against `provider:mtn:float` and refuse a disbursement that would overdraw
it.** Nkap would be modelling a balance it does not authoritatively hold — the operator
does — and every refusal would be a guess that could be wrong in both directions. It also
makes the gateway custody-shaped, which the positioning explicitly rejects. The operator's
answer is the authority; a `FAILED` for insufficient funds is that answer.

**A single `settlement:<ref>` entry id for both directions.** The prefix is cheap
legibility — a human reading `ledger_entry` sees which way each settlement went without
joining to `payment` — and it costs nothing.
