# ADR 0016 — A payment whose provider has no adapter is retried until its window is spent, then escalated

- **Status:** accepted
- **Date:** 2026-09-26

## Context

The reconciler claims a batch of due unresolved payments and asks the operator about each, through
`SettlementService.confirm`. `confirm` finds the adapter with `AdapterRegistry.require`, which
throws `NoAdapterConfiguredException` when none is configured for the payment's provider, and it
catches only `ProviderUnavailableException`. Nothing in `Reconciler.runOnce()` caught the other one
(issue #252). It left the loop, and `scheduledPass` logged `reconciler pass failed` and carried on.

That loop is also where escalation happens. `claimDue` has already advanced every claimed row's
`reconcile_attempts` and `reconcile_due_at`, in one transaction, before the first claim is
processed. So each claim after the one with no adapter was counted as attempted, was not asked,
and was not checked against its window. The payment with no adapter comes due again every
backoff interval, so this recurs for as long as its adapter is missing. A payment behind it can
spend its whole window without anyone being paged, because the code that pages is never reached.
The tests added with this ADR fail this way on the code before it: `NoAdapterConfiguredException`
out of `runOnce()`.

How a payment comes to have no adapter is not exotic. Since #220, a Cameroon slot whose country
is not stated configures no adapter, so a deployment upgraded without stating it holds `mtn-cm`
payments that nothing serves, and nothing says so. Taking an adapter out of configuration, or a
rollout in which one instance has it and another does not, has the same effect.

Refusing to start is not the answer. #218 and #220 made a gateway with no adapter at all a legal
installation, and `EmptyInstallIT` holds it to that: a first install starts before any operator is
configured. A refusal keyed on unresolved payments for unconfigured providers would leave first
installs alone. It would instead turn one provider's missing configuration into an outage for
every merchant on every provider.

## Decision

1. **The exception is caught per claim, around `confirm` alone, and it is the only exception
   caught.** The next claim in the batch is processed as usual. Any other exception is still a
   bug and still ends the pass, loudly, as before. A broad catch would turn the next unrelated
   bug into one more warning, and a failure hidden that way is what this issue is about.

2. **A payment with no adapter is escalated when its window is spent, with
   `reason=no_adapter`, and not before.** Until then, each pass that claims it logs one WARN
   naming the payment, the provider and its `reconcile_attempts`, and the payment stays in the
   queue.

3. **The reason is stored with the escalation.** `escalation_reason` (V12) is written by the
   same statement that stamps `escalated_at`, and `GET /actuator/escalatedPayments` reports it.
   The codes are `EscalationReason`'s: `window_exhausted`, `cannot_query`, `no_adapter`.

## Rationale

**Why escalate at all, instead of retrying forever.** The escalation guarantee is that an
unresolved payment is either resolved or put in front of a human within its window. A payment
nobody can ask about is the case that guarantee most needs to cover.

**Why at the window, not immediately.** ADR 0014 decision 3 escalates a `cannot_query` payment
with no operator call because it is provable that no later attempt can answer: the adapter
cannot poll and the payment holds no reference to poll with. A missing adapter proves nothing of
the kind. It is a configuration state, and configuration comes back: a restart with the right
variables, the next step of a rollout. The two look the same, a claim that cannot reach the
operator, but only the first is known to be permanent.

Escalating is one-way, which makes the difference matter. `CLAIM_DUE` filters
`escalated_at IS NULL`, so an escalated payment is never claimed again. The only code that
clears `escalated_at` is `Payment.applyTransition`, and it runs only when a payment enters
the unresolved states from outside them. The only such edge is from `CREATED`, and a `CREATED`
payment is never escalated, because `ESCALATE` requires an unresolved state. Every save of an
existing row reads it under `FOR UPDATE` in the same transaction, so a stale in-memory copy
cannot write the stamp away either. Escalating a payment the first time its adapter is missing
would therefore take it out of automatic reconciliation for good, even when the adapter is back
an hour later. That trades a silence for a loss.

**Why the catch sits around `confirm`, not around the claim.** A `try` around the whole claim
body, with a catch that logs and moves on, keeps the batch going. But the window check for the
payment with no adapter comes after `confirm` in that body, so the exception skips it, and that
payment is retried forever and never escalated. Around `confirm` alone, the catch hands the
payment to its own window check in the same pass.

**Why the reason is stored.** Before this, the endpoint recomputed the reason on each read.
`no_adapter` cannot be recomputed: it describes the deployment when the window was spent, and
the list is usually read after the adapter has been restored, when a recomputation would say
`window_exhausted`. The endpoint's own javadoc had already refused to recompute the configuration
half of `cannot_query`, for the same reason.

## Consequences

**Gained.**

- A payment with no adapter no longer stops the reconciliation or the escalation of the rest of
  its batch.
- A payment with no adapter is paged when its window is spent. Before, it was never escalated,
  and each pass instead logged an error.
- If the adapter comes back before the window is spent, the next pass resolves the payment with
  no human involved.
- Every escalation carries a stored reason, and the metric and the endpoint report the same
  code for it.

**Costs.**

- **Noise, by design.** A payment with no adapter logs one WARN each time it comes due until its
  window is spent: every backoff interval, for up to 24 hours at the default window. With many
  such payments, that is many lines. The alternative is silence, which is what this ADR removes.
- **The page comes late.** `NkapPaymentEscalated` fires on `no_adapter` with no rule change,
  since it filters on no reason, but only when the window is spent. Before that, the WARN lines
  are the only signal, and no metric counts payments waiting for an adapter. A deployment that
  loses an adapter learns it from its logs, on the first pass that claims one of that
  provider's payments, or from its pager when the window ends.
- **Escalation stays one-way, and restoring the adapter does not undo it.** A payment escalated
  for `no_adapter` is not re-queued automatically when its adapter returns. This is not handled,
  and here is what a human can do. Restore the provider's configuration first, since every
  payment for that provider is affected. Then list the payments with
  `GET /actuator/escalatedPayments`, whose `reason` is `no_adapter`, and ask the operator about
  each one. A callback from the operator to the payment's own URL still resolves it, through the
  restored adapter. No route resolves or re-queues a payment by hand. That gap is not specific
  to this reason: a `window_exhausted` payment has it too. Closing it would mean clearing
  `escalated_at`, which changes what escalation means. It needs its own decision and is not
  made here.
- **Rows escalated before V12 have no stored reason.** The endpoint keeps deriving one for them,
  as it always did. That derivation reports `cannot_query` only for a payment with
  `reconcile_attempts` at 0. Every escalation follows a claim, and every claim counts toward
  `reconcile_attempts`, so those rows read `window_exhausted` whatever their real reason was. It
  is left as it is: their reason was never recorded, and a guess written into the column would
  be worse than a known limit.

## Alternatives rejected

**Escalate at the first missing adapter, like `cannot_query`.** Rejected above: it removes from
the queue, for good, a payment that restoring the configuration would have resolved.

**Catch `RuntimeException` per claim.** It keeps the batch going for this failure and hides every
other one, including the next bug of this kind.

**Refuse to start while unresolved payments name an unconfigured provider.** Rejected in the
context: it turns one provider's problem into an outage for all of them.

**Recompute `no_adapter` on the endpoint from today's registry.** The reason would change as soon
as the adapter came back, at exactly the moment someone reads the list.
