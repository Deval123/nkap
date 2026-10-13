# ADR 0017 — An adjustment is proposed by one key and approved by another

- **Status:** accepted
- **Date:** 2026-10-13

## Context

A payment the reconciler cannot resolve is escalated, and `GET /actuator/escalatedPayments`
lists it. That is where the system stops. ADR 0016 says so among its own costs: restoring an
adapter does not un-escalate a payment, and no route resolves or re-queues one by hand. A human
who has found out what happened, from the operator's statement or its support desk, has nothing
to act with except SQL.

SQL is the wrong tool for the one thing that matters here. The ledger is append-only, and a
mistake in it is corrected by a compensating entry, so the entry a human would write is exactly
the kind of row the ledger exists to make hard to write carelessly. What is missing is a way to
write it that leaves a record of who proposed it, who agreed, and what they were looking at.

Until now the ledger had two ways in: a payment settling, and a statement import
(`StatementReconciliation`). Both are code paths that derive their postings from something the
operator said. This is a third, and it is the first whose postings come from a person.

## Decision

1. **A proposal is data until a decision approves it.** `POST /adjustments` records the
   payment reference it concerns, a hypothesis from a closed set, the postings, the evidence,
   a free-text explanation and the proposing key's `keyId`. It writes nothing to the ledger.

2. **Approval is the only thing that writes to the ledger, and the server writes the entry.**
   The entry id is `"adjustment:" + proposalId`, the same readable shape as
   `StatementReconciliation`'s `fee:<provider>:<txid>`. A second approval of the same proposal
   is refused by the `ledger_entry` primary key, which `PostgresLedger` maps to
   `DuplicateLedgerEntryException`, not by a guard a caller could race. The caller supplies
   neither the entry's id, nor its timestamp, nor its currency. The postings are the proposal's,
   as approved.

3. **A proposal and its decision are both append-only,** under the `nkap_forbid_mutation()`
   trigger the ledger and the payment history already use. One decision per proposal is a unique
   constraint on the proposal id.

4. **Seven checks stand between a proposal and the ledger:** the postings balance (the deferred
   constraint trigger is the guarantee, the Java check only gives a readable error); only
   only the payment's own provider's suspense, fee and provider-float accounts are named (the
   provider comes from the payment, never from the account string the caller wrote, so a proposal
   cannot mint an account this deployment never configured), never a merchant's payable; the amount
   is under a configured ceiling; the deciding key is not the proposing key; every value the
   proposal cites is present in its evidence; one currency, the payment's; and the whole feature
   is off unless configuration turns it on.

5. **Off by default, with its own refusal.** While `nkap.adjustments.enabled` is false, all
   five endpoints answer `501` with the problem type `adjustment-not-enabled`. It is not
   `feature-not-offered`: that type means the provider's adapter does not declare an operation
   (ADR 0013), and a client must be able to tell "this provider cannot do it" from "the operator
   switched it off". The status is the same for the same reason: the request is refused until
   the deployment changes.

## Consequences

**What is weakened.** The ledger had two ways in, both derived from what an operator said. It
has three, and the third is derived from what a person said. Section 4 compensates for that: the
database still refuses an unbalanced entry whatever the application does, no merchant's balance
can be reached, a second key must agree, the amount is bounded, and nothing is on until someone
turns it on. It is compensated, not removed. An approved adjustment is a real ledger entry, and
only a reversing entry undoes it.

**The separation of duties rests on key identity, not on a permission.** `ApiCredential` is
`(keyId, merchantId, admin)`, and `admin()` gates operator-wide reads, which a proposer may well
need. One boolean cannot tell a proposer from an approver. What separates them is that both rows
record their `keyId`, and the server refuses an approval whose `keyId` is the proposal's.
That has limits worth stating:

- It separates two keys, not two people. One person holding two keys can approve their own
  proposal. Issuing and revoking keys is the control on that, and it is not strengthened here.
- Any valid key can approve, not only a designated approver.
- Proper scopes, a proposer scope and an approver scope on the key, are the stronger answer and
  a later slice. This is not equivalent to them and is not offered as such. `api_key` is not
  changed here: it still carries `merchant_id` and `is_admin` and nothing more.

**What a human still cannot do.** This slice does not un-escalate a payment, does not re-queue
it for the reconciler, and does not change its state. An approved adjustment writes a balanced
ledger entry and records who decided. The payment stays escalated, and
`GET /actuator/escalatedPayments` still lists it. ADR 0016's cost is half paid, not closed: the
money can now be put right, and the payment's state cannot.

**A refused proposal is not stored.** A proposal that fails a check at proposal time (an account
it may not name, an amount over the ceiling, a citation with nothing behind it, a currency that
is not the payment's) is answered with its problem type and writes no row. The row would be
data that a later approval could act on. A refusal at decision time, such as a ceiling lowered
since the proposal was made, is recorded as a rejection with its reason. A self-approval is
refused and not recorded. A decision is final: the unique constraint allows one per proposal and
the table is append-only, so nothing can take it back. Recording the refusal would therefore
spend that one decision, and a proposer could lock every other key out of the proposal by
approving it themselves, with no way to undo it.

**The ceiling is a number, not a judgement.** It bounds a single proposal and does not bound how
many are made. A run of small proposals approved one by one is limited only by the approver.

**Two keys are needed to try it,** and both are merchant keys or admin keys of the existing
kinds. A deployment with one key cannot approve anything, which is intended.

## Alternatives rejected

**A `role` or `scopes` column on `api_key`.** The stronger design, and a change to the
authentication model that this slice does not make. It is a later slice, and nothing here blocks
it: the `keyId` check stays true under scopes.

**Let the proposer supply the ledger entry whole.** The caller would choose the entry's id,
timestamp and accounts. The server derives all three instead, so a proposal cannot name an
entry that already exists or backdate one.

**Re-queue the payment for the reconciler on approval.** It changes what escalation means
(ADR 0016) and needs its own decision. Out of scope here.

**Reuse `feature-not-offered` for the off state.** Rejected in decision 5.
