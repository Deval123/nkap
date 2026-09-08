# provider-mtn

The MTN MoMo Collections adapter — the first implementation of `ProviderAdapter`.

The adapter **translates and nothing more**. State transitions, idempotency and
bookkeeping are the core's job. This module turns one `PaymentIntent` into MTN's
`requesttopay` call and MTN's answers back into a `SubmitResult` / `ProviderStatus`; where
it cannot map an answer with confidence it says `UNKNOWN` or throws
`ProviderUnavailableException`. It never invents a failure.

Design and observed behaviour: [ADR 0004](../docs/adr/0004-mtn-adapter.md) and
[`docs/providers/mtn.md`](../docs/providers/mtn.md).

## Dependencies

- `provider-api` (and, through it, `core`).
- Jackson for JSON — a library, not a framework.
- `java.net.http.HttpClient` — in the JDK.
- **No Spring.** An adapter must be embeddable in a plain JVM process. The tests depend on
  `nkap-simulator` at test scope only.

## Shape

| Class | Responsibility |
| --- | --- |
| `MtnProfile` | Everything one installation needs — base URL, target environment, subscription key, API user + key, settlement currency, country. Validated on construction. One adapter, one profile. |
| `MtnCollectionsAdapter` | The `ProviderAdapter`: `submit`, `query`, `parseCallback`. Advertises only `COLLECT`. |
| `MtnTokenCache` | The bearer token, refreshed on a margin *before* expiry and once more on a 401. Concurrent callers share one in-flight refresh. |
| `MtnStatusMap` | MTN's status and error codes → `PaymentState`, as data. Closed: anything absent is `UNKNOWN`. |

## How answers are mapped

**submit** — returns a `SubmitResult`. `202` is `Acknowledged(SUBMITTED)`; the body is empty
by contract. A `409` is *also* `Acknowledged`: the reference was already used, which — since
Nkap persists it before calling — means a previous attempt reached MTN, and the query will
settle it. A `400` is `SubmitResult.Rejected`, carrying MTN's `code` and `message` — a
definitive "this request is invalid", closed by ADR 0005. Anything else, a timeout, or an
unreadable body is `ProviderUnavailableException`.

**query** — `200` is mapped through `MtnStatusMap`; `reason` is consulted before `status`,
so a `FAILED` carrying `SERVICE_UNAVAILABLE` (the operator's own system failing mid-answer)
comes out `UNKNOWN`. `financialTransactionId` is read into `ProviderStatus.transactionId()`
when the payment has settled, and is absent while it is pending. A `404`
(`RESOURCE_NOT_FOUND`) is **`UNKNOWN`, not a failure** — Nkap persists the reference first,
so a 404 is "never arrived" or "not visible yet", and one response cannot tell them apart;
the reconciler concludes after its window. Anything else is `ProviderUnavailableException`.

**auth** — a `401` despite a live token triggers exactly one refresh and one retry **with
the same reference**. A second `401` is `ProviderUnavailableException`.

The token call carries no body and **must send `Content-Length: 0`** — MTN answers `411`
with an HTML page otherwise. `BodyPublishers.noBody()` produces that header, and a test
asserts it on the wire so a change of HTTP client cannot break it silently.

## Tests

`mvn -B -pl provider-mtn -am test`. Everything runs against the **simulator** (booted on a
random port), driven through the scenarios it already knows: the happy path, timeout then
late success, a duplicate reference, a flapping status, token expiry mid-flight, an unknown
status code, a `400`, a `500`.

`MtnSandboxIT` is the one exception: it talks to the real MTN sandbox, is disabled unless
`NKAP_MTN_SANDBOX` is set, reads credentials from `~/.nkap/mtn-sandbox.env`, and never runs
in CI.
