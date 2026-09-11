# ADR 0008 — An adapter is handed everything it needs to translate

- **Status:** accepted
- **Date:** 2026-09-11

## Context

This is ADR 0005 happening a second time, and it belongs beside it for that reason.

ADR 0005 recorded a contract written before any adapter existed getting one thing wrong:
`SubmitResult` claimed a submission could only report `SUBMITTED` or `PENDING`, and MTN's
`400` disproved it. The remedy then was to change the contract while there was exactly one
adapter to change with it.

`query(ReferenceId)` is the same mistake in a different method. It assumes a reference is
enough to ask an operator what became of a payment. MTN's two products disprove it:
Collections answers on `/collection/v1_0/requesttopay/{ref}` and Disbursements on
`/disbursement/v1_0/transfer/{ref}`, so the adapter must know which product it is asking
before it can form the request at all.

The disbursements slice (issue #62) shipped a workaround: the composition root injected a
`Function<ReferenceId, Optional<Capability>>` into `MtnAdapter`, which read the operation
off the payment the gateway had already recorded, with a `COLLECT` fallback for the gap
that could not happen. It worked and it was tested. It was also backwards, in a way that
matters more than the inconvenience:

- `provider-api` says an adapter translates and never decides. That adapter had to consult
  **gateway state** in order to translate.
- The caller already held the answer. `SettlementService.confirm` has the payment in hand
  when it calls `query`, and was passing a reference while keeping the operation to itself.
- The conformance kit does not cover that path — its harness drives `MtnCollectionsAdapter`
  directly — so the one part of the adapter no rule of the kit defended was the workaround.
- A second operator would have copied it, because it was there and it worked.

## Decision

**`query` takes the capability the reference was submitted under.**

```java
ProviderStatus query(ReferenceId reference, Capability capability) throws ProviderUnavailableException;
```

`MtnAdapter` routes on the argument. The injected lookup and its `COLLECT` fallback are
gone, and `MtnConfiguration` no longer sees the payment store at all.

**`balance` takes one too**, for the same operator fact: MTN holds a separate balance per
product, behind the same two base paths.

```java
Money balance(Capability capability, Currency currency) throws ProviderUnavailableException;
```

**`parseCallback` deliberately takes neither.** A webhook is the operator's own message and
carries whatever it carries; for MTN a callback is the same JSON for both products and both
parsers map it through the same `MtnStatusMap`. There is nothing product-specific to route
on, so there is no argument to add.

The boundary those three decisions draw, which is the part a future contributor needs:

> **An adapter is handed everything it needs to translate.** If an adapter has to look
> something up — read gateway state, consult a payment it cannot see — to do its job, the
> contract is missing an argument.

## Rationale

**Why change the contract rather than keep the lookup.** The lookup is not merely
inelegant; it inverts the module boundary the project is built on. `core` owns state,
`provider-api` translates, and the reason adding an operator cannot introduce a
bookkeeping bug is that an adapter has no access to bookkeeping. An adapter that reaches
back into gateway state to answer a question has that access, and the next one will use it
for something less innocent than a base path. The cost of removing it is a signature change
across one adapter and its call sites; the cost of keeping it compounds with every operator
added.

**Why `balance` changes now, with no caller.** This looks like speculative generality and
is worth separating from the case it resembles. The test is not "does an implementation use
the parameter today" but "is there a fact that requires it". For `parseCallback` there is
no such fact — the payload carries everything, identically for both products — so adding a
parameter there would be symmetry for its own sake, and the contract's javadoc says so to
stop someone completing the pattern later. For `balance` the fact is already on the table:
MTN publishes `/collection/v1_0/account/balance` and `/disbursement/v1_0/account/balance`,
and the only reason no implementation uses the argument is that no implementation of
`balance` exists yet. Changing it in the same pass that changes `query` costs one line;
changing it when the account-balance slice lands costs the same churn a second time, for a
defect of exactly the class this ADR exists to close. Fixing one class of defect twice is
how a contract gets churned.

## Consequences

- Every call site of `query` passes a capability. In `server` there is one —
  `SettlementService.confirm` — and it passes the payment's own `intent().operation()`.
- `MtnConfiguration`'s bean is built from `MtnProperties` alone. `MtnConfigurationTest`
  asserts it declares no `PaymentRepository` parameter, so the lookup cannot grow back
  quietly through the composition root.
- `MtnCollectionsAdapter` and `MtnDisbursementsAdapter` each reject a capability that is
  not theirs, rather than silently answering for the wrong product.
- The conformance kit's rules pass a capability. It did **not** gain a rule that a reference
  submitted under one capability is answered under that capability; the pull request for
  issue #67 records why, and what would have to change first.
- No change to `core`, which has never known what a `Capability` is.

## Alternatives rejected

**Keep the injected lookup and document it.** Documenting an inverted dependency does not
un-invert it, and the workaround's own javadoc already conceded the cleaner shape was
`query(ReferenceId, Capability)`. A comment saying "this is debt" is not a substitute for
paying it while it is cheap.

**Give `MtnAdapter` two `ProviderId`s, one per product.** Rejected in issue #62 and still
rejected: `ProviderId` names the operator, not the product, and `ConfiguredAdapterRegistry`
is right to refuse two adapters claiming one id. A merchant chooses MTN, not MTN
Collections.

**Put the capability inside `ReferenceId`.** It would make every signature shorter and is
much worse. `ReferenceId` is `core`'s, `Capability` is `provider-api`'s, and rule 3 says
`core` has no dependencies — this would invert that too. A reference is an identity; what
was done under it is not part of its identity.

**Wait for a second operator to confirm the shape.** That is precisely the reasoning ADR
0005 rejected. The contract is cheapest to change when one adapter implements it, and a
second operator arriving is the moment it stops being cheap.
