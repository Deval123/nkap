# MTN MoMo — what the API actually does

Facts observed against the sandbox on 2026-09-08, not copied from documentation. Where the
two disagree, this file wins and says so. The design decisions that follow from these facts
are in [ADR 0004](../adr/0004-mtn-adapter.md).

Keep this file honest and specific. An operator's undocumented quirks are worth more to a
contributor than the adapter's code, which they can read.

## Getting credentials

Each country has its own developer portal with its own user store: an account on one does
not exist on another. The generic sandbox lives at `momodeveloper.mtn.com`; a country
portal such as `momodeveloper.mtn.co.rw` is a separate installation with separate
credentials.

Products are subscribed to separately — Collections, Disbursements, Remittances — so each
carries its own subscription key. A subscription name is a label for the developer's own
benefit; it is never sent and never validated.

Sandbox provisioning, once a Collections subscription exists:

```
POST /v1_0/apiuser                      X-Reference-Id: <uuid you choose>  → 201
POST /v1_0/apiuser/{X-Reference-Id}/apikey                                 → 201 {"apiKey": …}
POST /collection/token/                 Basic auth: apiUser:apiKey         → 200 {"access_token", "expires_in": 3600}
```

The UUID you send as `X-Reference-Id` when creating the user *is* the API user id from then
on. `providerCallbackHost` is fixed at creation and cannot be changed; it is an allow-list,
and a later `X-Callback-Url` outside it is rejected with `INVALID_CALLBACK_URL_HOST`.

## Quirks that cost time

**A bodyless POST needs an explicit `Content-Length: 0`.** Both the API-key call and the
token call carry no body, and the gateway answers **HTTP 411 Length Required** — with an
HTML error page, not JSON — when the header is absent. Some HTTP clients send
`Transfer-Encoding: chunked` instead, which is refused just the same. This affects the token
call, the most frequent call the adapter makes.

**Error bodies are `{"message": …, "code": …}`.** Map on `code`. `message` is prose for a
human and must never be parsed.

**Response fields are conditional.** A `PENDING` status returns:

```json
{"externalId":"probe-1","amount":"5000","currency":"EUR",
 "payer":{"partyIdType":"MSISDN","partyId":"46733123453"},
 "payerMessage":"…","payeeNote":"…","status":"PENDING"}
```

No `financialTransactionId`, no `reason` — those appear only once the payment settles. An
adapter that requires them fails on every pending payment, which is most of them.

**A 202 really is empty.** `Content-Length: 0`, no body at all. The outcome is only ever
available through the query.

**The sandbox settles in EUR** whatever country you think you are testing, and the documented
test MSISDN `46733123453` stays `PENDING` for well over three seconds. A test that submits
and immediately expects success fails for reasons that have nothing to do with the code
under test.

## Observed responses

| Call | Result |
| --- | --- |
| `POST /collection/v1_0/requesttopay` | `202`, empty body |
| same `X-Reference-Id` again | `409` `{"code":"RESOURCE_ALREADY_EXIST"}` — a previous attempt reached MTN; not an error |
| `GET /collection/v1_0/requesttopay/{ref}` | `200` with the payload above |
| `GET` on a reference never submitted | `404` `{"code":"RESOURCE_NOT_FOUND"}` |

## Disbursements

Not observed against a real MTN — mapped from the documented shape and driven through the
simulator (issue #62). Treated as **structurally identical to Collections** until a real
call proves otherwise:

- A **separate product**: its own subscription key, API user and key, and token endpoint
  `POST /disbursement/token/`. `provider-mtn` has a whole `MtnDisbursementsAdapter` with its
  own `MtnProfile` and token cache; `MtnAdapter` is one adapter over both. In the gateway
  the credentials are `nkap.provider.mtn.disbursement.*`.
- `POST /disbursement/v1_0/transfer` — `202` with an empty body, `X-Reference-Id` the
  idempotency key, exactly like `requesttopay`. The body names the counterparty **`payee`**
  where a collection says `payer`.
- `GET /disbursement/v1_0/transfer/{ref}` — assumed to return the same
  `{status, reason, financialTransactionId}` shape; `404` `RESOURCE_NOT_FOUND` for an
  unknown reference, handled as `UNKNOWN` the same way.
- The same status/error codes and the same conservatism: an unrecognised code is `UNKNOWN`.
  A transfer refused for lack of funds is an ordinary `FAILED` with `NOT_ENOUGH_FUNDS` — the
  gateway does not predict it or hold a reserve (see
  [ADR 0007](../adr/0007-what-a-settled-disbursement-posts.md)).
- The ledger effect is the mirror of a collection (ADR 0007); the fee rule is unchanged
  (ADR 0006).

## Still unknown

Left open deliberately rather than guessed. Each is worth a pull request adding a line here.

- Which sandbox MSISDNs produce which failure codes, and how long each takes to settle.
- What a `SUCCESSFUL` and a `FAILED` status actually contain, field by field.
- Whether production returns codes absent from the documentation.
- The shape and headers of a real callback, and whether it is ever the only notification.
- **Whether a real Disbursements `transfer` and its status differ from what the *Disbursements*
  section above assumes** — a different response field, a code Collections does not use, a
  callback shaped differently. Mapped from documentation and the simulator only; a real call
  is the thing to check it against, and this line moves up into that section when one is made.
- **How an operator statement is obtained** — a portal download, a report API, an emailed
  file, an SFTP drop — and on what cadence. Nothing fetches one today; statement
  reconciliation is a host-side command (`--nkap.statement.import=<path>`) run against a
  file already on the machine, not a network endpoint.
- **What a statement line contains, field by field.** Statement reconciliation was built
  against an *invented* provider-neutral model (`server`'s `statement` package): per line,
  an operator transaction id, a gross amount in minor units, a fee (may be absent), a
  timestamp, and an outcome of `SETTLED` or `FAILED`. The reconciler matches on the
  transaction id — the same value a settled `ProviderStatus` carries as
  `financialTransactionId` — and compares the gross amount; a fee, when present, posts
  `DR fees:mtn:<CCY> / CR provider:mtn:float:<CCY>` per [ADR 0006](../adr/0006-what-a-settled-collection-posts.md).
  The CSV layout the placeholder parser reads (`CsvStatementParser`) is equally ours:
  `operator_transaction_id,amount_minor,fee_minor,currency,occurred_at,status`. When a real
  MTN statement is seen, this file records what it holds and the parser is the only code
  that changes — reconciliation, persistence and the report all work in the neutral model.
