# Outgoing webhooks

What Nkap sends a merchant when a payment reaches a verdict, and the contract that comes
with it. The mechanics — the outbox, the relay, the retry policy — are in ADR 0003 and the
javadoc of `dev.nkap.server.outbox`; this file is what an integrator needs, not how it is
built.

## What is sent, and when

A webhook fires for a **terminal outcome**: `payment.succeeded`, `payment.failed`,
`payment.expired`. That is the verdict a merchant is waiting for — the same three states
`GET /payments/{reference}` reports once it stops needing to be polled.

An escalated payment does **not** produce a webhook. Escalation means the reconciler gave
up retrying automatically and paged a human — it is an operator event, not a merchant one,
and "we do not know yet" is not something a merchant's system can act on by receiving it
twice a day.

## The payload

```json
{
  "id": "3fbf6f2e-2f42-4e6a-9d0b-6d4b6a9c2b41",
  "type": "payment.succeeded",
  "reference": "b3f1c9d2-8b7a-4b2e-9e2a-1f6c8a9d0b3e",
  "provider": "mtn",
  "operation": "COLLECT",
  "amountMinor": 5000,
  "currency": "EUR",
  "state": "SUCCEEDED",
  "providerCode": "SUCCESSFUL",
  "providerTransactionId": "628f36",
  "occurredAt": "2026-09-11T14:32:07.123Z"
}
```

`reference` is the same value `GET /payments/{reference}` takes — a webhook is a push of
the same fact a poll would eventually see, not a separate model. `amountMinor` is an
integer count of minor units, the same rule as everywhere else in this project: never a
decimal. `providerTransactionId` is set only for `payment.succeeded`; a failure or
expiry has no operator transaction to point to.

**`id` is the field to deduplicate on.** See the next section for why it has to be.

## At-least-once, and no ordering

Delivery retries on failure, which means **a merchant can receive the same event more than
once.** A receiver that is not idempotent on `id` will double-process a payment the second
time a retry lands after the first attempt actually succeeded but the response was lost in
transit.

There is also **no ordering guarantee** across events. Two payments that settle moments
apart may arrive in either order, or a retried older event may arrive after a fresher one
for a different payment. Nothing here assumes a merchant's receiver is single-threaded or
that "the request I got most recently is the newest fact" — check `GET
/payments/{reference}` if an event's relative ordering matters, since that read always
reflects the current state, not the state at the time some particular event was queued.

*(This same pair of facts — retries mean duplicates, and there is no ordering guarantee —
belongs in the eventual integration guide, issue #65, as more than a cross-reference: an
integrator reading a runnable example should see the deduplication check written out, not
just told it exists.)*

## Verifying a signature

Every request carries an `Nkap-Signature` header:

```
Nkap-Signature: t=1757602327,v1=5257a869e7bfce951fbb7d3fb8fe28c3b4b9c3a2b2e0d3a1f4b8c9c0d1e2f3a4
```

`t` is the Unix timestamp (seconds) the request was signed at. `v1` is the hex-encoded
HMAC-SHA256 of `"{t}.{body}"` (the timestamp, a literal `.`, then the raw request body),
keyed with the endpoint's signing secret.

To verify:

1. Recompute `HMAC-SHA256(secret, "{t}.{raw body}")` over the **raw** bytes received —
   before any JSON parsing, which can silently normalise whitespace or key order.
2. Compare it to `v1` in constant time (`hmac.compare_digest`, `crypto.timingSafeEqual`, or
   your language's equivalent — never `==` on the hex strings).
3. Reject the request if `t` is further than a few minutes from your own clock. This is
   what stops a captured, valid request from being replayed later: without it, a signature
   alone proves the body wasn't tampered with, not that the request is fresh. Nkap's own
   relay treats a request as fresh within `nkap.webhooks.signature-tolerance`
   (`application.yml`, five minutes by default) — a receiver that enforces a *tighter*
   window than that will reject some legitimate retries.

## The secret is not a hash

An API key is hashed at rest and only ever compared; the gateway never reads one back. A
webhook signing secret is different: it has to be **used**, not just checked, so it is
stored in a form the gateway can read. See `docs/positioning.md` for what that means for
who can forge a notification.

Provision an endpoint from the host, the same way an API key is provisioned — a command,
never a route:

```
java -jar app.jar --nkap.webhook.create --nkap.webhook.merchant=<id> --nkap.webhook.url=<url>
```

The secret is printed once. Re-running the command for a merchant that already has an
endpoint replaces it — one endpoint per merchant in this slice, so provisioning and
rotating are the same operation.

## Retries, and giving up

A failed delivery is retried with a growing delay (`nkap.webhooks.backoff-base`, doubling,
capped at `nkap.webhooks.backoff-max`). After `nkap.webhooks.max-attempts` attempts, the
event is **dead-lettered**: delivery stops, but the event is never deleted — it stays
findable, and `POST /webhooks/events/{eventId}/replay` (an admin credential; see
`ProblemTypes.ADMIN_REQUIRED`) resends it by hand once whatever was wrong on the receiving
end is fixed.

## Not in this slice

Multiple endpoints per merchant. Per-event-type subscriptions. A Kafka connector — ADR 0003
already says that is optional, and optional means later.
