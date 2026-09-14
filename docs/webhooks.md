# Outgoing webhooks

What Nkap sends a merchant when a payment reaches a verdict, and the contract that comes
with it. The mechanics — the outbox, the relay, the retry policy — are in ADR 0003 and the
javadoc of `dev.nkap.server.outbox`; this file is what an integrator needs, not how it is
built.

## What is sent, and when

A webhook fires for a **terminal outcome**: `payment.succeeded`, `payment.failed`,
`payment.expired`. That is the verdict a merchant is waiting for — the same three states
`GET /payments/{reference}` reports once it stops needing to be polled.

A refund (`POST /payments/{reference}/refunds`, ADR 0010) is a payment and fires the same
way, but under its own type — `refund.succeeded`, `refund.failed`, `refund.expired` — never
`payment.*`. A merchant must be able to tell "your refund went through" from "your
disbursement went through" without inspecting any other field.

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
  "occurredAt": "2026-09-11T14:32:07.123Z",
  "refundOf": ""
}
```

`reference` is the same value `GET /payments/{reference}` takes — a webhook is a push of
the same fact a poll would eventually see, not a separate model. `amountMinor` is an
integer count of minor units, the same rule as everywhere else in this project: never a
decimal. `providerTransactionId` is set only for a `*.succeeded` event; a failure or
expiry has no operator transaction to point to.

`refundOf` is `""` for every event that is not a refund's. A `refund.succeeded` event carries
the original collection's own reference there — the same one `operation` alone cannot tell
you, since a refund's `operation` is `DISBURSE` like any other transfer out:

```json
{
  "id": "9c1a1f7e-6b2a-4a1e-8d9c-2f6e7a1b3c4d",
  "type": "refund.succeeded",
  "reference": "7a2e0c1f-5b4a-4d3e-9c8b-1a2b3c4d5e6f",
  "provider": "mtn",
  "operation": "DISBURSE",
  "amountMinor": 2000,
  "currency": "EUR",
  "state": "SUCCEEDED",
  "providerCode": "SUCCESSFUL",
  "providerTransactionId": "628f41",
  "occurredAt": "2026-09-13T09:12:44.501Z",
  "refundOf": "b3f1c9d2-8b7a-4b2e-9e2a-1f6c8a9d0b3e"
}
```

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

The url must be `https`: this is a signed notification about money, and the signature
protects integrity, not confidentiality — a typo giving `http://` would send it in clear
text. `nkap.webhooks.allow-insecure-endpoint-url=true` lifts that for a local demo or a test
with no TLS in front of it; a real deployment never sets it.

## Retries, and giving up

A failed delivery is retried with a growing delay (`nkap.webhooks.backoff-base`, doubling,
capped at `nkap.webhooks.backoff-max`). After `nkap.webhooks.max-attempts` attempts, the
event is **dead-lettered**: delivery stops, but the event is never deleted — it stays
findable. `GET /webhooks/events/dead-lettered` (an admin credential; see
`ProblemTypes.ADMIN_REQUIRED`) lists them, oldest first, which is how a dead-lettered event
is discovered instead of found by already knowing its id; `nkap_outbox_dead_lettered`
(`docs/prometheus-alerts.yml`) is how it gets noticed in the first place. `POST
/webhooks/events/{eventId}/replay` (same credential) resends one by hand once whatever was
wrong on the receiving end is fixed.

## Not in this slice

Multiple endpoints per merchant. Per-event-type subscriptions. A Kafka connector — ADR 0003
already says that is optional, and optional means later.
