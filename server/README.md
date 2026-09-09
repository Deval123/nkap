# server

The Nkap gateway: the HTTP surface that assembles `core`, an adapter, and the payment
lifecycle into something that runs.

Two slices so far. The **first** (issue #30) creates a payment over HTTP, hands it to MTN
and reads its state back. The **second** (issue #36) closes the loop: a callback confirmed
by `query()` can carry a payment to `SUCCEEDED`, and settlement writes the first entries
this project has ever put in the ledger.

Stores are in memory. That is a step towards PostgreSQL, which implements the same
interfaces (`IdempotencyStore`, `PaymentRepository`, `Ledger`), not a feature: there is no
flag that selects it and no documented mode.

## Endpoints

### `POST /payments`

```json
{
  "merchantId": "merchant-1",
  "operation": "COLLECT",
  "amount": 5000,
  "currency": "EUR",
  "counterpartyMsisdn": "46733123453",
  "payerMessage": "rent",
  "payeeNote": "march"
}
```

- `amount` is an **integer count of minor units** — `5000` XAF is 5000 francs, `5000` EUR
  is 50.00. A fractional number is a **400**, before anything is persisted.
- `currency` must be the one this deployment settles in (`nkap.provider.mtn.currency`). A
  request in any other currency is a **400** with its own problem type, before a payment or
  an idempotency claim exists: no operator was asked and nothing is unknown — the caller
  addressed an installation that does not serve that currency.
- The **`Idempotency-Key` header is required**. Absent, that is a 400. A repeat with the
  same key and body replays the first answer verbatim; the same key with a different body
  is a **409**; a key whose first request is still running is also a **409**, with a
  distinct problem type.
- The reference is generated and **persisted in `CREATED` before the adapter is called** —
  the downstream half of idempotency, and why a retry is safe.
- The three submit outcomes, mapped honestly:

  | operator said | payment | HTTP |
  | --- | --- | --- |
  | acknowledged | `SUBMITTED` / `PENDING` | **201** |
  | refused the request | `FAILED`, with the operator's code | **201** — the request was valid, the payment exists, the operator said no |
  | did not answer | `UNKNOWN` | **202** — the outcome must be polled; a 500 here would invite a retry under a new key and a double payment |

### `GET /payments/{reference}`

Reads stored state and the full transition history. It **does not call the operator** — a
read that hits a third party is a read that times out, and closing an `UNKNOWN` is the
reconciler's job. `404` if unknown, `400` if the reference is not well-formed.

### `POST /callbacks/{providerId}`

The operator's webhook. It is **unauthenticated** — MTN sends no signature we have
observed — and safe anyway because it settles nothing by itself: a callback is a hint that
asking is now worthwhile, and `adapter.query()` is the only authority. The worst a forged
callback achieves is making the gateway ask a question it was entitled to ask.

- **202** whenever the gateway took responsibility for the message — **including a callback
  naming a reference it never issued**. A `404` there would let a caller probe which
  references exist. Nothing is written for an unknown or untrusted callback.
- **400** only when the body cannot be parsed as that provider's callback. That says
  nothing about our data.
- **404** when `{providerId}` names no configured adapter.

For a callback that names a payment we hold, the gateway calls `query()` and applies its
answer, cause `CALLBACK`:

- a definite `SUCCEEDED` settles the payment and posts to the ledger (below); `FAILED` /
  `EXPIRED` are recorded and post nothing;
- if the query does not answer, or answers `UNKNOWN`, **nothing changes** — a failed second
  opinion must not erase what we already knew;
- a callback that arrives before the submit response finds the payment in `CREATED`. It
  records `CREATED → SUBMITTED` first — receiving a callback proves the operator has the
  request — then the confirmed state. Two transitions, both true; the state machine is not
  widened.

Read-decide-write is serialised per reference (an in-process lock; PostgreSQL will use
`SELECT … FOR UPDATE` on the payment row).

**The gateway does not tell MTN where to send callbacks.** The callback URL is configured
on the MTN product; `POST /callbacks/mtn` is that URL. Sending `X-Callback-Url` per request
waits until the gateway knows its own public address — deployment configuration, a later
change.

**Authenticating this endpoint is deferred to its own issue.** For MTN it means source-IP
allow-listing, which is deployment configuration rather than code. Confirm-by-query is what
keeps the endpoint safe until then.

### Settlement — what a `SUCCEEDED` collection posts

Per ADR 0006, the **gross** amount, two postings, in the payment's currency:

```
DR  provider:<providerId>:float:<CCY>     amount
CR  merchant:<merchantId>:payable:<CCY>   amount
```

No fee posting — operator fees arrive later through statement reconciliation, and
`ProviderStatus.providerFee` is recorded on the payment but never reaches the ledger.
Exactly-once is structural: the entry id is derived from the reference, so a second
settlement is refused by `Ledger.append`, not written twice.

## Errors

`application/problem+json` (RFC 7807) for every error, from one `@RestControllerAdvice`,
each with a stable `type` under `https://nkap.dev/problems/` so a client can branch on it
without parsing prose.

## Configuration

Credentials come from the **environment**, never a committed file:

| Variable | Example |
| --- | --- |
| `NKAP_PROVIDER_MTN_BASE_URL` | `https://sandbox.momodeveloper.mtn.com` |
| `NKAP_PROVIDER_MTN_TARGET_ENVIRONMENT` | `sandbox` |
| `NKAP_PROVIDER_MTN_SUBSCRIPTION_KEY` | *(secret)* |
| `NKAP_PROVIDER_MTN_API_USER` | *(secret)* |
| `NKAP_PROVIDER_MTN_API_KEY` | *(secret)* |
| `NKAP_PROVIDER_MTN_CURRENCY` | `EUR` |
| `NKAP_PROVIDER_MTN_COUNTRY` | `sandbox` |

A missing value leaves the field blank and the context refuses to start — a clear failure
at boot rather than the first payment failing. Which provider a `POST /payments` routes to
is `nkap.provider.default` (`mtn`); routing by country arrives with multi-country support.

## Not in these slices

Outgoing webhooks, the reconciler, statement reconciliation and operator fees, refunds,
disbursements, balance, holder validation, PostgreSQL, authenticating the callback endpoint
(its own issue — source-IP allow-listing, deployment configuration). No payment reaches
`SUCCEEDED` without a callback and a confirming `query()`.
