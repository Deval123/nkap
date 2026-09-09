# server

The Nkap gateway: the HTTP surface that assembles `core`, an adapter, and the payment
lifecycle into something that runs.

This is the **first vertical slice** (issue #30). A payment is created over HTTP, reaches
the operator through the MTN adapter, and its state can be read back. **Nothing settles and
nothing is written to the ledger** — only `SUCCEEDED` moves money, and no payment reaches
`SUCCEEDED` without the callback path, which is the next slice.

Stores are in memory. That is a step towards PostgreSQL, which implements the same
interfaces (`IdempotencyStore`, `PaymentRepository`), not a feature: there is no flag that
selects it and no documented mode.

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

## Not in this slice

The ledger, operator fees (an accounting decision that deserves an ADR), callbacks,
PostgreSQL, disbursements, balance, holder validation, the reconciler.
