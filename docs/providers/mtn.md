# MTN MoMo — what the API actually does

Facts observed against the sandbox on 2026-09-08, not copied from documentation. Where the
two disagree, this file wins and says so. The design decisions that follow from these facts
are in [ADR 0004](../adr/0004-mtn-adapter.md).

Keep this file honest and specific. An operator's undocumented quirks are worth more to a
contributor than the adapter's code, which they can read.

The only other operator page today is
[`docs/providers/orange-money.md`](orange-money.md) — read, not implemented, and worth
reading first for the contrast: everything on that page is *assumed*, more weakly sourced
than anything here, because nobody on this project holds an Orange developer account. This
file's observed/assumed discipline is what makes that admission meaningful instead of a
formality.

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

## Multiple countries in one deployment

Issue #82: one deployment configures several country installations at once, under
`nkap.provider.mtn.installations` — each entry is everything above (its own portal, its own
subscription keys, its own currency) plus the `country` field that names it. A country
installation is registered as `mtn-<country>` (`ProviderId`), derived from that field, never
configured separately — see `MtnConfiguration`. `POST /payments` names the country it wants;
an unconfigured one is a `400` naming what is configured, not a server error.

**Configured in this repository's demo and default reference deployment:**

| Country | `ProviderId` | Currency |
| --- | --- | --- |
| Cameroon | `mtn-cm` | XAF |
| Ghana | `mtn-gh` | GHS |

**Merely possible — MTN operates there, `Currency` has the right minor-unit entry, but no
installation is configured here**: Benin, Republic of Congo, Côte d'Ivoire, Guinea,
Guinea-Bissau, Liberia, Nigeria, Rwanda, South Africa, Uganda, Zambia, and others MTN's own
footprint covers. `Currency` is the gateway's vocabulary, kept in `core` and answering only
"can this gateway count in it, with its minor units verified" — this page is the separate
record of what MTN coverage this adapter configures. The two lists overlap, since most of
what MTN settles in has to be countable, but neither defines the other: `Currency` also
holds members no MTN installation will ever use (`EUR`, `USD`, `KES` — Kenya is Safaricom's
M-Pesa, not an MTN market), and a country appearing below is not itself a claim that
`Currency` was extended *for* it. A `Currency` member is not a claim that the country
works — it is only ever a claim that, if an installation for that country were configured,
its minor-unit count would be right. Wiring one in is adding an entry to
`nkap.provider.mtn.installations` with its own credentials; nothing about the adapter, the
ledger accounts (per installation — see
[ADR 0009](../adr/0009-accounts-are-per-installation.md)) or the conformance kit changes to
support it.

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

## Status and error mapping

`MtnStatusMap` (package-private, `provider-mtn`) is the single place every MTN status and
error code becomes a `PaymentState`. The table below is a copy for reading, not a second
source of truth: `MtnStatusMappingDocTest`, beside `MtnStatusMap`, reads this file and
asserts the two agree in both directions — every code in the map has a row here, every row
here names a real code, and the state in the row is the state the map actually returns.
Change one without the other and the build fails.

**The table is closed, and anything absent is `UNKNOWN`.** A code MTN has not documented, a
typo, or a new one added to their API tomorrow is not a failure this gateway can assert —
only `UNKNOWN`. This is not merely intended: the conformance kit holds it as a rule every
adapter must pass (issue #80, `ProviderAdapterConformanceTest.an_unrecognised_answer_is_unknown`),
so an adapter that mapped an unrecognised code to `FAILED` would fail its own kit, not just
disagree with a comment.

**`reason` is consulted before `status`.** `MtnStatusMap.stateFor` checks `reason` first,
then `status`, then the error body's `code` — whichever is non-blank first wins. The
consequence is the least obvious behaviour in this adapter: a query or callback reporting
`status: FAILED, reason: SERVICE_UNAVAILABLE` comes out `UNKNOWN`, not `FAILED`, because the
`reason` — the operator's own system failing mid-answer — is what actually gets consulted,
and it says nothing about whether the payment itself succeeded or failed.

| Code | Appears in | Nkap records | Documented by MTN |
| --- | --- | --- | --- |
| `SUCCESSFUL` | `status` | `SUCCEEDED` | yes |
| `PENDING` | `status` | `PENDING` | yes |
| `EXPIRED` | `status` | `EXPIRED` | yes |
| `FAILED` | `status` | `FAILED` | no |
| `PAYER_NOT_FOUND` | `reason` | `FAILED` | yes |
| `PAYEE_NOT_FOUND` | `reason` | `FAILED` | yes |
| `NOT_ENOUGH_FUNDS` | `reason` | `FAILED` | yes |
| `PAYER_LIMIT_REACHED` | `reason` | `FAILED` | yes |
| `APPROVAL_REJECTED` | `reason` | `FAILED` | yes |
| `INVALID_CURRENCY` | `reason` | `FAILED` | yes |
| `NOT_ALLOWED` | `reason` | `FAILED` | yes |
| `INVALID_CALLBACK_URL_HOST` | `reason` | `FAILED` | yes |
| `SERVICE_UNAVAILABLE` | `reason` | `UNKNOWN` | yes |
| `INTERNAL_PROCESSING_ERROR` | `reason` | `UNKNOWN` | yes |
| `RESOURCE_NOT_FOUND` | error body `code`¹ | `UNKNOWN` | yes |

¹ This column names where MTN's own vocabulary places the code, not where `stateFor` reads
it from for this row. `MtnCollectionsAdapter`/`MtnDisbursementsAdapter`'s query handling maps
any `404` to `UNKNOWN` unconditionally, without calling `stateFor` and without inspecting the
body's `code` at all. That is deliberate, not an unwired lookup: see *Three namespaces, one
flat map* below for why routing it through the table would be a regression, not a tidy-up.

**Three rows carry the weight, and a reader will not believe them until they are argued.**
`SERVICE_UNAVAILABLE` and `INTERNAL_PROCESSING_ERROR` are the operator saying *its own*
system broke — which says nothing about where the money went, so `UNKNOWN`, not `FAILED`.
`RESOURCE_NOT_FOUND` on a query means MTN has never seen the reference; since Nkap persists
it before calling, that is either "never arrived" or "not visible yet", and one response
cannot tell them apart — the reconciler does, after its window (see *Observed responses*
below for the fuller argument, and `MtnStatusMap`'s own javadoc for the same reasoning next
to the code it defends).

**`FAILED` is in the map and not documented by MTN — that asymmetry is deliberate, not an
omission.** ADR 0004 documents the fourteen codes marked "yes" above; a bare `FAILED` status
with no recognised `reason` is Nkap's own interpretation, not an MTN vocabulary entry — MTN's
own documentation never lists `FAILED` as a value of the `reason` field, only as the `status`
value that carries one of the other codes. Treating an unadorned `FAILED` as `FAILED` rather
than `UNKNOWN` is a narrow, deliberate exception to "anything absent is `UNKNOWN`": the
operator's `status` field itself is never absent or unrecognised here, only its `reason` is,
and a `status` of literally `FAILED` is already the verdict — there is nothing left for a
missing `reason` to cast doubt on.

### Three namespaces, one flat map

The table is one flat map, but its keys come from three different namespaces — the "Appears
in" column above: `status` values (`SUCCESSFUL`, `PENDING`, `FAILED`, `EXPIRED`), `reason`
values, and the `code` field of a non-200 error body. Flattening them into one
`Map<String, PaymentState>` works because the tokens happen to be disjoint across MTN's
documented vocabulary, and it is what makes `stateFor` a one-liner — but it is an assumption,
not a guarantee, and nothing checks that a future code MTN adds to one namespace does not
collide with an existing one in another.

**The error-body-`code` namespace is not wired into `stateFor` the way `status` and `reason`
are, and that is not a gap — it is a stronger rule than the table can express.** Every call
to `stateFor` in `MtnCollectionsAdapter` and `MtnDisbursementsAdapter` passes an empty string
for that argument, and the one place a real `code` would naturally reach it — the query
path's `404` branch — never calls `stateFor` at all. It returns `UNKNOWN` unconditionally,
on purpose: a `404` moments after submission is indistinguishable from "not visible yet",
and the adapter's own comment calls this "the least intuitive rule in the adapter" (see
*Observed responses* below). Routing that branch through `stateFor` would let a `404` whose
body happened to carry a recognised `FAILED` reason code — say, `NOT_ENOUGH_FUNDS` — come out
`FAILED` instead of `UNKNOWN`, which is exactly the softening that rule exists to prevent:
this project does not treat "the answer looks like a failure" as license to skip the
one-response-cannot-tell-them-apart argument. `RESOURCE_NOT_FOUND`'s row in the table above
is real, and records the mapping ADR 0004 documents for that code, and
`MtnStatusMapTest.the_table_covers_every_documented_code` asserts it via `MtnStatusMap.isKnown`
— but the row is not what runs for a `404`, and consulting it there would be a regression,
not a tidy-up. A *second* error-body code, one no unconditional branch already overrides,
would be the first to actually reach `stateFor` through this namespace — worth remembering
before assuming this one already proves the wiring works.

## Observed responses

| Call | Result |
| --- | --- |
| `POST /collection/v1_0/requesttopay` | `202`, empty body |
| same `X-Reference-Id` again | `409` `{"code":"RESOURCE_ALREADY_EXIST"}` — a previous attempt reached MTN; not an error |
| `GET /collection/v1_0/requesttopay/{ref}` | `200` with the payload above |
| `GET` on a reference never submitted | `404` `{"code":"RESOURCE_NOT_FOUND"}` |
| `GET /collection/v1_0/requesttopay/{ref}` for a submission to MSISDN `46733123450` | `INTERNAL_PROCESSING_ERROR` — on every query, over 36 hours |

Every row above was actually seen against the sandbox. What a `400` on submission means is
not: no real MTN account has produced one yet, so it stays here as **assumed**, from ADR
0004's documented vocabulary and `MtnCollectionsAdapter.submit`'s own handling, not from
observation — the same distinction the *Still unknown* section keeps elsewhere. `submit`
treats any `400` as an outright refusal: no payment exists under this reference, the body's
`code` is meant to be one of the `FAILED` reason codes from the mapping above, and the
gateway records `CREATED → FAILED` directly rather than calling `query()` at all. Whether a
real `400` body actually carries one of those codes, or something else entirely, is exactly
the kind of thing worth confirming against a real account and moving up out of *assumed* once
someone has.

**A query on a reference MTN never saw is `UNKNOWN`, not `FAILED` — and it stays `UNKNOWN`
forever if MTN genuinely never received it.** `MtnCollectionsAdapter`/
`MtnDisbursementsAdapter` map this `404` the same way for both products: not as a verdict,
because a `404` moments after submission is indistinguishable from "the request has not
propagated to whichever node answers reads yet" (see the code's own comment — "the least
intuitive rule in the adapter"). There is no separate signal for "never existed" versus "not
visible yet", so this project's rule — a timeout is never a failure — is applied here too.
This matters for issue #84's refunds: a refund payment stranded in `CREATED` (the reservation
committed, but the process was killed before the transfer was ever submitted) is swept to
`UNKNOWN` after a grace period and queried like anything else. If MTN truly never received
it, every query comes back `RESOURCE_NOT_FOUND` → `UNKNOWN`, forever — it does **not**
self-heal to `FAILED`. It escalates once its window is spent, the same as any other stuck
payment, and a human resolves it by hand once they have confirmed with MTN that the transfer
never happened.

### MTN's published test MSISDNs, and what they actually answer

MTN's developer documentation publishes a table of test MSISDNs said to produce determined
outcomes — Failed, Rejected, Timeout, Success, Pending. **That table is not reproduced
here, because it was not observed here.** Two of its own details do not line up: the
"Success" entry carries a different leading pair of digits from the other four, and
`MtnSandboxIT`'s own default MSISDN appears in neither list. A table this project has not
run is not a fact this project can vouch for.

What has actually been observed, against a real sandbox account, on 2026-09-15 and
2026-09-16:

| MSISDN | What a status query answered | When |
| --- | --- | --- |
| `46733123450` | `INTERNAL_PROCESSING_ERROR`, every time, across two payments and 36 hours | 2026-09-15, 2026-09-16 |
| `46733123453` | `PENDING` shortly after submission; terminal state never observed | 2026-09-15 |

`46733123450` is documented by MTN as the *Failed* case, and it does not answer as one.
It would be easy, and wrong, to conclude that MTN's table is simply incorrect. **No
callback has ever been received by this project from the real sandbox** — MTN cannot
reach a local deployment — so the documented outcome may arrive that way instead, rather
than through the status query. This page does not choose between the two readings: the
sandbox's status endpoint may genuinely be unreliable for this MSISDN, or the *Failed*
verdict may exist only on a callback nobody here has ever been in a position to receive.
Until one arrives, the published table describes something this project has not seen.

The practical consequence for anyone exercising the sandbox today: **`46733123450` does
not currently produce a `FAILED` payment in Nkap.** It produces an escalation — see the
next section.

### What Nkap does when the real operator answers nothing conclusive

A payment submitted for `46733123450` was accepted (`202`, `CREATED → SUBMITTED`), and
every subsequent status query answered `INTERNAL_PROCESSING_ERROR` — the operator saying
*its own* system failed, which says nothing about where the money went. `MtnStatusMap`
maps it to `UNKNOWN`, deliberately, and the reconciler treated it accordingly. Observed in
Nkap's own logs:

```
09:26:24  RECONCILER … not conclusive (INTERNAL_PROCESSING_ERROR), changing nothing
09:27:24  RECONCILER … not conclusive (INTERNAL_PROCESSING_ERROR), changing nothing
09:29:24  RECONCILER … not conclusive (INTERNAL_PROCESSING_ERROR), changing nothing
09:33:25  RECONCILER … not conclusive (INTERNAL_PROCESSING_ERROR), changing nothing
09:41:25  RECONCILER … not conclusive (INTERNAL_PROCESSING_ERROR), changing nothing
…
escalated to a human after 6 reconciler attempt(s); operator's last answer: INTERNAL_PROCESSING_ERROR
```

Six attempts on an exponential backoff — one minute, two, four, eight, and onward — then,
once the window was spent, an escalation. **The payment was never marked `FAILED`**, on
any of the six occasions it could have been guessed at, and it never will be by this
mechanism: only the operator's own conclusive answer can make it terminal. This is the
project's founding rule — a timeout is never a failure — observed against a real operator
instead of the simulator that was built to model it.

Two further things this run showed, neither observed before:

- **The reconciler's schedule survives a process restart.** The stack was down for
  roughly thirty-six hours between the fifth attempt and the sixth. The attempt count and
  the retry window are columns on the payment, not state held in memory, so the sixth
  attempt escalated correctly rather than starting the count over.
- **A non-conclusive answer leaves no trace on the payment.** `updatedAt` stayed at the
  millisecond of submission throughout, across all six attempts. That is correct — nothing
  about the payment's own state changed — but it means a payment actively being chased is,
  through `GET /payments/{reference}`, indistinguishable from one nobody has looked at
  since it was created. Issue #113 tracks that gap.

### What the simulator answers with

The simulator errors in this same shape everywhere, so an adapter driven against it can
always read a `code` — an empty error body would be a fiction no real operator produces
(issue #26). Only the two rows above are observed; the rest it chooses:

| Simulator case | Response |
| --- | --- |
| reused `X-Reference-Id` | `409` `RESOURCE_ALREADY_EXIST` — observed |
| `GET` on an unknown reference | `404` `RESOURCE_NOT_FOUND` — observed |
| missing or malformed `X-Reference-Id` | `400` `INVALID_REFERENCE_ID` — **not an MTN code**, see *Still unknown* |
| scenario `onSubmit.outcome: CONFLICT` | `409` `RESOURCE_ALREADY_EXIST` |
| scenario `onSubmit.outcome: BAD_REQUEST` | `400` `NOT_ALLOWED` — documented vocabulary (ADR 0004), chosen as a default |
| scenario `onSubmit.outcome: SERVER_ERROR` | `500` `INTERNAL_PROCESSING_ERROR` — documented vocabulary, chosen as a default |

A scenario that needs a particular code declares one rather than relying on those defaults:
`"onSubmit":{"outcome":"BAD_REQUEST","code":"INVALID_CURRENCY"}`. The field is optional and
the status still comes from the outcome. `ACCEPT` and `NO_RESPONSE` are untouched by this —
a 202 really is empty, and a non-answer really is nothing at all.

The `/_nkap/` control plane deliberately keeps its own error shape: it does not imitate MTN,
and a contributor who mistyped a scenario is better served by a 400 naming the field.

**The simulator never sends `financialTransactionId`, on any status, scripted or not** —
`RequestToPayController.status` (and its disbursement equivalent) answers with only
`{status, reason}`. Real MTN sends it once a payment settles (see *Quirks that cost time*
above). The consequence a reader hits directly: `GET /payments/{reference}` and every webhook
for a payment settled against the simulator carry `providerTransactionId: ""`, even for
`SUCCEEDED` — not a Nkap bug, and not something the simulator was asked to model here.

## Disbursements

Not observed against a real MTN — mapped from the documented shape and driven through the
simulator (issue #62). Treated as **structurally identical to Collections** until a real
call proves otherwise:

- A **separate product**: its own subscription key, API user and key, and token endpoint
  `POST /disbursement/token/`. `provider-mtn` has a whole `MtnDisbursementsAdapter` with its
  own `MtnProfile` and token cache; `MtnAdapter` is one adapter over both. In the gateway
  the credentials are each installation's own `nkap.provider.mtn.installations[n].disbursement.*`.
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

## Account balance and account-holder validation

Not observed against a real MTN — mapped from the documented shape, driven through the
simulator (issue #72). Available under both products, at their own base paths, the same
way a balance and a status query are:

- `GET /collection/v1_0/account/balance` and `GET /disbursement/v1_0/account/balance` —
  assumed to return `{"availableBalance": "<decimal>", "currency": "<code>"}`, the decimal a
  major-unit string the same shape `requesttopay`'s own `amount` field uses, just the other
  direction. The adapter converts it to an exact count of minor units or refuses — see
  `MtnCollectionsAdapter.minorUnitsFromMtnAmount`; it never rounds. A response naming a
  currency other than the one asked for is refused, not coerced.
- `GET /collection/v1_0/accountholder/msisdn/{msisdn}/active` and the `/disbursement/v1_0/`
  equivalent — assumed to return `{"result": <true|false>}`. Whether MTN actually sends a
  JSON boolean or a `"true"`/`"false"` string is unconfirmed, so the adapter reads either;
  anything else is `UNKNOWN`, the same conservatism `MtnStatusMap` applies to a payment
  status. Whether the account-holder answer genuinely differs by product, or one product's
  answer would do for both, is also unconfirmed — each product asks its own path because
  each product is a separate set of credentials, not because the two are known to disagree.
- Both are `GET`s that call the operator on every request. Neither has a reference or a
  timeline, so the simulator answers them from declared configuration (`AccountBehaviour`),
  not a `Scenario` — see ADR 0002's scope.

## Still unknown

Left open deliberately rather than guessed. Each is worth a pull request adding a line here.

- **Whether any sandbox MSISDN produces a terminal outcome through the status endpoint.**
  `46733123450` answers `INTERNAL_PROCESSING_ERROR` indefinitely; `46733123453` answered
  `PENDING` and its terminal state was never seen. The three remaining published numbers
  have not been exercised, and no run against the real sandbox has yet produced a
  `SUCCESSFUL` or a `FAILED`.
- **Whether the real sandbox sends callbacks at all**, and whether the outcomes MTN's
  test-MSISDN table describes arrive that way rather than through the status endpoint.
  Untestable from a deployment MTN cannot reach; it needs a publicly reachable
  `providerCallbackHost`.
- **What a real `400` on submission actually contains.** Assumed from ADR 0004's vocabulary
  and `MtnCollectionsAdapter.submit`'s own handling (*Observed responses* above); no real
  account has produced one yet, so it is not yet known whether the body's `code` is always
  one of the mapping's `FAILED` reason codes or something this table does not yet name.
- **What MTN answers to a missing or malformed `X-Reference-Id`.** The simulator answers
  `400` with `INVALID_REFERENCE_ID`, which is *its own* code: no observation records MTN's,
  and inventing one that looked documented would be worse than an obviously local name. An
  unrecognised code maps to `UNKNOWN` anyway, so nothing depends on the guess.
- What a `SUCCESSFUL` and a `FAILED` status actually contain, field by field.
- Whether production returns codes absent from the documentation.
- The shape and headers of a real callback, and whether it is ever the only notification.
- **Whether a real Disbursements `transfer` and its status differ from what the *Disbursements*
  section above assumes** — a different response field, a code Collections does not use, a
  callback shaped differently. Mapped from documentation and the simulator only; a real call
  is the thing to check it against, and this line moves up into that section when one is made.
- **The exact shape of a real Account Balance response.** Field name, decimal format,
  whether a currency mismatch can even occur or is only a defensive check against a fact
  that never happens. Mapped from documentation only — see *Account balance and
  account-holder validation* above.
- **The exact shape of a real Account Holder response**, including whether `result` is a
  JSON boolean or a string, and any code or field this adapter would currently map to
  `UNKNOWN` for lack of a recognised shape.
- **Whether Collections and Disbursements genuinely answer an account-holder check the
  same way**, or whether one product's endpoint is the one that actually matters and the
  other is untested surface. Both are called today because both are configured products;
  neither has been proven against a real MTN account.
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
