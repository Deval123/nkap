# ADR 0004 — The MTN adapter: one profile per country and product, and a closed status map

- **Status:** accepted
- **Date:** 2026-09-08

## Context

`provider-api` was designed before any adapter existed. MTN is the first implementation and
therefore the first real test of that contract.

Three facts from MTN's own documentation shape this decision, and none of them was obvious
from the outside:

- **Each country is its own installation.** Countries have separate developer portals, and
  `X-Target-Environment` carries `sandbox` in the sandbox and a country value in production.
  A country is not a parameter inside one account; it is a whole set of credentials.
- **Each product is its own account too.** Collections and Disbursements have separate
  dashboards, so separate subscription keys.
- **The error codes are documented**, and they are not all the same kind of thing.

## Decision

### 1. The unit of configuration is a profile: provider, country, product

A profile holds everything needed to talk to one MTN installation for one product: base URL,
`X-Target-Environment`, subscription key, API user, API key, and the currency that
installation settles in.

Nkap holds many profiles at once. A single global credential set cannot work and would have
to be undone the first time a second country is added.

### 2. The caller names the country. Nkap never guesses it

A payment request carries its country explicitly. Nkap resolves the profile from it, then
**verifies that the profile's currency matches the payment's currency** and refuses the
request if it does not.

Inferring the country would be easy and wrong. XOF is shared by eight countries and XAF by
six, so currency does not identify a country. MSISDN prefixes look more promising and are
worse: number portability, roaming and shared ranges all defeat them, and the failure is
silent — the payment succeeds against the wrong installation and lands in the wrong float
account. A ledger that is right by construction cannot be fed by a guess.

### 3. The status map is a table, closed by default

Every documented code maps explicitly. Anything not in the table — an undocumented code, a
new one, a typo — maps to `UNKNOWN`.

| MTN says | Nkap records | Why |
| --- | --- | --- |
| `SUCCESSFUL` | `SUCCEEDED` | The operator confirmed the movement. |
| `PENDING` | `PENDING` | The payer has not answered yet. |
| `PAYER_NOT_FOUND`, `PAYEE_NOT_FOUND` | `FAILED` | The account does not exist. Answered, and the answer is no. |
| `NOT_ENOUGH_FUNDS`, `PAYER_LIMIT_REACHED` | `FAILED` | Answered: refused. |
| `APPROVAL_REJECTED` | `FAILED` | The payer declined. |
| `EXPIRED` | `EXPIRED` | The payer never answered in time. |
| `INVALID_CURRENCY`, `NOT_ALLOWED`, `INVALID_CALLBACK_URL_HOST` | `FAILED` | Our request was wrong. Answered. |
| **`SERVICE_UNAVAILABLE`** | **`UNKNOWN`** | The operator's own system failed. It is not telling us the payment failed — it is telling us it does not know. |
| **`INTERNAL_PROCESSING_ERROR`** | **`UNKNOWN`** | Same. |
| anything else | `UNKNOWN` | A code we do not recognise is not a failure we can assert. |

The last three rows are the reason this project exists. A naive adapter sees an error code
and concludes failure; but "my system broke" says nothing about where the money went. Those
outcomes belong to the reconciler, not to a verdict.

### 4. A 409 on submission is not a failure

`RESOURCE_ALREADY_EXIST` on `requesttopay` means the reference was already used — which,
since Nkap generates and persists the reference before calling, means **a previous attempt
reached MTN**. That is good news: idempotency worked and nothing was duplicated.

The adapter therefore treats a 409 as "already submitted" and queries for the outcome. Never
as an error. This is the single most likely place for an implementer to introduce the bug
this project is built to prevent, so it is called out here rather than left to be inferred.

### 5. Tokens are cached per profile, refreshed ahead of expiry, refreshed once on 401

One token cache per profile, since profiles do not share credentials. Renewal happens on a
safety margin before expiry rather than on the first rejection, and concurrent callers share
a single in-flight refresh rather than stampeding. A 401 despite a live token triggers one
refresh and one retry **with the same reference**; a second 401 is `ProviderUnavailableException`.

### 6. Transport failures are unknowns, not failures

A timeout, a connection failure, a 5xx or an unreadable body throws
`ProviderUnavailableException`, which the gateway maps to `UNKNOWN`. The adapter never turns
silence into a verdict.

### 7. Disbursements require an allow-listed IP, and that must fail legibly

MTN requires the caller's IP to be allow-listed before disbursements are permitted. It is a
human process with the operator, not a setting. When it has not been done the API refuses,
and the adapter must say so in those terms rather than surfacing a bare 403 — otherwise the
first disbursement attempt in production becomes an evening of debugging something that is
not a bug.

## Consequences

- Configuration is a list of profiles, validated at startup: a profile missing a credential
  or naming an unknown currency fails the boot rather than the first payment.
- The status map is data, and a test asserts every documented code appears in it. Adding a
  country never means editing the map.
- Ledger accounts are per profile currency, which is what §3 of the v1.0.0 scope already
  requires.
- Sandbox and production differ only by profile values. No code branches on "is this the
  sandbox", which is the usual source of things that work everywhere except in production.
- `provider-api` survives this design unchanged. `PaymentIntent.providerOptions` carries the
  country; if that proves too weak once written, the contract changes — and that discovery is
  the reason this adapter is being built before any other.

## Alternatives rejected

**One global credential set with the country as a parameter.** Contradicted by MTN's own
structure: separate portals, separate keys, separate dashboards per product.

**Inferring the country from the MSISDN or the currency.** Silent, wrong, and it corrupts the
ledger rather than failing.

**Mapping unknown codes to `FAILED`.** The tempting default, and the exact bug this project
exists to prevent.
