# ADR 0006 — What a settled collection posts to the ledger

- **Status:** accepted
- **Date:** 2026-09-09

## Context

The server can create a payment, hand it to MTN and report its state. It cannot yet settle
one, because settling means writing to the ledger, and nobody has decided what the entry
contains. `PaymentState.movesMoney()` says only `SUCCEEDED` writes, and only once; it does
not say what.

The accounts already exist as `AccountId` factories: a provider **float** (funds Nkap holds
at an operator — an asset), a merchant **payable** (what Nkap owes a merchant — a
liability), a **fees** bucket, and **suspense** (money the operator confirms moved but which
Nkap cannot yet attribute).

Two questions get conflated and are separate:

1. **The operator's fee.** MTN takes a cut. Does it reduce what the merchant is owed, or is
   it a cost Nkap absorbs?
2. **Nkap's own fee.** Whether the gateway charges for its service at all.

The second is easy: it does not, in 1.0.0. Nkap is not a payment institution, holds no
funds, and the roadmap prices nothing. The `fees` account exists for the first question.

**One fact shapes everything, and it is an absence.** `docs/providers/mtn.md` lists, under
*Still unknown*: "What a `SUCCESSFUL` and a `FAILED` status actually contain, field by
field." We have never observed a settled collection in production, and the sandbox has not
shown a fee. `ProviderStatus` carries an optional `providerFee` because the contract allowed
for one, not because MTN was seen to send one.

So the real question is not "gross or net". It is: **may the ledger entry depend on a field
we have never seen?**

## Decision

**A settled collection posts the gross amount, in two postings, and nothing else.**

```
DR  provider:mtn:float:XAF        amount
CR  merchant:acme:payable:XAF     amount
```

Operator fees do not enter through the payment path. They enter through statement
reconciliation (§4 of the roadmap) as **their own entries**, when the operator's statement
says what they actually were:

```
DR  fees:mtn:XAF                  fee
CR  provider:mtn:float:XAF        fee
```

A `FAILED` or `EXPIRED` payment posts nothing — no money moved. An `UNKNOWN` payment posts
nothing either: `UNKNOWN` means we do not know, and a ledger is not where guesses go.
Suspense is for the opposite case — the operator confirms a movement Nkap cannot attribute —
and the reconciler is what puts anything there.

**The fee entry debits `fees:mtn`: Nkap absorbs the operator's fee**, and the merchant is
credited the full amount they collected. This is a commercial decision, recorded here
because it is the one thing in this document a reader cannot derive from the code. The
alternative — crediting the merchant net, by debiting `merchant:<id>:payable` instead — is
the same entry with a different debit, and can be adopted later without touching anything
else described here.

Absorbing it is chosen for a reason worth stating: a merchant reconciles Nkap against what
their customers paid. A merchant credited the full collected amount can do that with
arithmetic they already trust. A merchant credited net has to know the operator's tariff to
check their own statement, and every question about a missing hundred francs becomes a
support conversation about MTN's pricing.

## Rationale

**A settlement must never be blocked by a field that may not arrive.** If the entry needed
the fee, then a `SUCCEEDED` payment whose status carries no fee could not be written at all.
The gateway would have to stall, guess, or invent a zero — and inventing a zero is a lie
that compounds silently across every payment. Posting gross is always possible, from data
the payment itself carries.

**The operator's statement is the authority on fees, not the payment API.** Even if MTN did
report a fee per transaction, the number that matters is the one on the statement — it is
what the money actually did. §4 already requires importing statements and comparing them to
the ledger. Fees arriving through that path means they are recorded from the source that can
be reconciled, rather than from a response nobody can audit later.

**Append-only makes the two-step natural.** A fee discovered a day after settlement is not a
correction of the first entry; it is a second entry about a second real event. That is what
an append-only ledger is for. Had we posted net and guessed, fixing it would need a
reversal — the original entry preserved forever alongside its contradiction, for a number
that was never observed.

**It keeps the adapter dumb.** `provider-api` already says an adapter translates and does
not decide. Deriving a ledger entry from an operator's optional field would put an
accounting decision inside whichever adapter happened to populate it, and a second operator
would make a different one.

## Consequences

- **Between settlement and statement import, `provider:mtn:float` overstates what MTN
  actually holds**, by the accumulated fees. This is not a defect to hide: it is a real,
  measurable gap, and the reconciler reporting it is the system working. It is also why the
  statement importer is in 1.0.0 rather than after it.
- `ProviderStatus.providerFee` stays in the contract and stays unused by the ledger. It is
  recorded on the payment for the record and for the reconciler to compare against the
  statement. An unused field is a smaller cost than a field we would have to add back.
- The callback slice can be built now. Its ledger write is two postings from the payment's
  own amount and needs nothing from the operator beyond the confirmation.
- Multi-currency needs nothing special here: both postings are in the payment's currency, and
  `LedgerEntry` already refuses to mix two.
- When MTN's settled-status fields are finally observed, `docs/providers/mtn.md` gets the
  answer and this ADR gets a dated correction if it changes anything. It probably will not:
  seeing a fee reported would not make the statement less authoritative.

## Alternatives rejected

**Post net — credit the merchant `amount − fee` at settlement.** Requires the fee at
settlement time, which the operator may never send. It is the most accurate entry when the
data is there, and unwritable when it is not, which makes it the wrong default. If MTN turns
out to report a reliable fee on every settled collection, this becomes worth revisiting —
as a correction to this ADR, with the observation that justifies it.

**Post gross plus an accrued fee estimate from a configured rate.** Three postings, one of
them a number nobody sent us. A ledger whose entries contain estimates cannot be reconciled
against anything, because every discrepancy is ambiguous between "the estimate was wrong"
and "something is broken". The suspense balance stops being a health metric.

**Put the fee in suspense until the statement arrives.** Misuses the account. Suspense means
"the operator says money moved and we cannot say whose it is" — an anomaly a human must
resolve. A fee we have not been told yet is not an anomaly; it is a fact we do not have. If
routine settlement filled suspense, its balance would stop meaning what §4 needs it to mean.

**Let the merchant's contract decide, per merchant, in 1.0.0.** There is no merchant model
yet — `merchantId` is a string on a request. Building fee agreements before merchants exist
is scope growth of the kind the roadmap's out-of-scope section exists to prevent.
