# M-Pesa — what the sandbox actually does

Facts observed against Safaricom's sandbox on 2026-09-18, not copied from documentation or
from a third-party client. Where a third party was the starting point, this file says so and
says whether the run confirmed it (see *Sources*). There is no M-Pesa adapter in this
repository, no ADR, and no issue opened from what follows — naming the consequences for
`provider-api` and for this project's own assumptions is this page's job; deciding them is a
separate step, for someone else to take.

The two other operator pages are [`docs/providers/mtn.md`](mtn.md) and
[`docs/providers/orange-money.md`](orange-money.md), and the contrast with each is worth
stating before anything else. `mtn.md` keeps an **observed / assumed** discipline this page
borrows exactly rather than reinvents. `orange-money.md` is entirely *assumed* — every
statement on it more weakly sourced than anything here, because nobody on this project holds
a developer account for Orange's authoritative API reference. **This page is the opposite of
`orange-money.md`, and matches `mtn.md`'s own discipline from the first day rather than
reaching it over several: almost every statement below was executed against Safaricom's
sandbox on 2026-09-18 and is reported as observed.** It is the first time this project has
written a provider page from observation on its first day.

## Getting into the sandbox

**The sandbox is self-service.** An account was created with an email address — no company,
no registered-business documents, no contract. An app named `nkap_sandbox` was created against
two products, `Lipa Na M-Pesa Sandbox` and `M-Pesa Sandbox`, and its Consumer Key and Secret
were issued immediately. This matters beyond convenience: it is the property
`orange-money.md`'s access-gate finding says Orange lacks, and the one that let MTN be first.

**Going live is gated, and that gate sits elsewhere.** The portal's *Go Live* flow needs an
organisation short code, an M-PESA Org portal Business Administrator, and an OTP sent to a
Safaricom line. The commercial barrier exists — it sits before production, not before
observation.

## Where M-Pesa exists, and what this page cannot say about it

Kenya (Safaricom), Tanzania (Vodacom), Lesotho, DRC, Ghana, Mozambique, Egypt, Ethiopia.
Withdrawn from India (2019), Romania (2017) and Albania (2017); the same source names South
Africa separately, as a market where the service struggled to gain traction, not as one it
was withdrawn from — worth keeping distinct rather than folding it into the withdrawn three.
This list comes from Wikipedia's own M-Pesa article, not from Safaricom, so it stays here as
**assumed** — the weakest-sourced statement on this page, and it should look it (see
*Sources*).

What matters more than the list, and needs checking rather than asserting: the Daraja portal
every observation on this page came from is **Safaricom Kenya's**. Trade press describes
Vodacom Tanzania opening a separate M-Pesa API to developers, and a portal exists at
`openapiportal.m-pesa.com` — but no Safaricom or Vodacom page was read that confirms what it
covers, or that "M-Pesa Open API" is its real name rather than a label this page borrowed from
that portal's own URL. That gap — third-party material naming a second API family, nothing
official read to confirm it — is why the claim sits in *Still unknown* below rather than being
asserted here as settled.

Why this would matter if true: MTN's adapter serves several countries through one API, which
is what makes [ADR 0009](../adr/0009-accounts-are-per-installation.md)'s "several
installations, one adapter per operator" shape work. If M-Pesa Kenya and M-Pesa Tanzania need
different APIs, one commercial name needs **two adapters**, and "an adapter per operator"
stops describing what is actually being built. That is a real question for this project's
model; this page's job is to raise it, not settle it.

## Authentication and submission

`GET /oauth/v1/generate?grant_type=client_credentials` on `https://sandbox.safaricom.co.ke`,
HTTP Basic with the Consumer Key and Secret. `200`, with `access_token` (28 characters) and
`expires_in: 3599`. A one-hour credential is exactly what the conformance kit's
credential-renewal case exists for.

**Submission** is `POST /mpesa/stkpush/v1/processrequest`, bearer token. The request's
`Password` is `base64(BusinessShortCode + Passkey + Timestamp)`, `Timestamp` formatted
`yyyyMMddHHmmss` — confirmed by decoding the example value in Safaricom's own documentation,
not taken from a third party:

```json
{"BusinessShortCode":174379,"Password":"<base64>","Timestamp":"20260918153000",
 "TransactionType":"CustomerPayBillOnline","Amount":"1","PartyA":"254708374149",
 "PartyB":"174379","PhoneNumber":"254708374149","CallBackURL":"https://example.invalid/nkap/mpesa",
 "AccountReference":"<merchant-chosen>","TransactionDesc":"<merchant-chosen>"}
```

**Note for whoever writes the adapter: in that same documented example, `BusinessShortCode`
is a JSON number while `Amount` and `PartyB` are strings** — including `PartyB`, which carries
the identical value as `BusinessShortCode`. A serializer that treats the two consistently
because they hold the same number will produce a request Safaricom's own example does not
match.

The response carries `MerchantRequestID`, `CheckoutRequestID`, `ResponseCode: 0`,
`ResponseDescription`, `CustomerMessage`. **`AccountReference` — the only field the caller
chooses — is not echoed.**

**`CheckoutRequestID` encodes Nairobi local time.** `ws_CO_180920261803512708374149` for a
submission this recorder timestamped `15:03` UTC: the identifier reads `18:03:51`, UTC+3.
Worth knowing before anyone parses one, and a reason not to.

## Querying, and what querying reveals

`POST /mpesa/stkpushquery/v1/query`, bearer token, `{BusinessShortCode, Password, Timestamp,
CheckoutRequestID}`. Against a real, pending `CheckoutRequestID` it answers `200` and a state:
`ResultCode: 1037`, `ResultDesc: "No response from user."` — the sandbox payer never answers
the prompt, which makes this the timeout case for free. The vocabulary is a state vocabulary,
like MTN's; an `MpesaStatusMap` would have the same shape as `MtnStatusMap`.

**The same call with a reference Safaricom does not recognise answers `HTTP 500`**,
`errorCode 500.001.1001`, `errorMessage: "The transaction does not Exist"`. Record the `500`
as its own finding: *does not exist* and *our server is broken* arrive as the same status, so
an adapter cannot tell them apart and must map both to `UNKNOWN`. The invariant survives — by
making an informative answer unusable.

**There is no idempotency on the caller's reference.** Two submissions carrying an identical
`AccountReference` were both accepted and produced two different `CheckoutRequestID`s and two
different `MerchantRequestID`s. This is the question `orange-money.md` calls "the single most
important thing to know before writing an adapter", asked and answered.

## The callback

**Three samples were taken for the callback URL, and what the validator actually checks is
not resolved by them.** `https://example.invalid/nkap/mpesa` — a host that by RFC 2606 can
never resolve — was accepted, which rules out "the host must resolve" as the rule.
`https://LHOTE.trycloudflare.com/nkap/mpesa` was rejected with `400.002.02`, `"Bad Request -
Invalid CallBackURL"`. `https://upgrading-wagon-corpus-apnic.trycloudflare.com/nkap/mpesa` was
accepted — a live Cloudflare quick-tunnel host, so the callback path is observable without a
domain of one's own, when it is accepted.

Between the rejected sample and the accepted one, two variables changed at once: uppercase
letters, and a subdomain that was not a live tunnel at the time. Nothing here isolates which
one the rejection was actually about. **Uppercase letters are the leading hypothesis**, not a
finding: the first sample already rules out a non-resolving or non-existent host as a reason
to reject, which leaves uppercase as the more likely explanation for the second sample's
rejection, by elimination rather than by a test that varied it alone. This is the mirror image
of MTN, which checks the host against a list fixed at API-user creation (issue #116). What
the validator actually checks belongs in *Still unknown* below.

**The callback itself** was delivered 26 seconds after submission, `POST` to the exact path
supplied, from `196.201.212.69` (`Cf-IPCountry: KE`), `Content-Type:
application/json;charset=UTF-8`, carrying a `BusinessShortCode` header. Body:

```json
{"Body":{"stkCallback":{"MerchantRequestID":"…","CheckoutRequestID":"…","ResultCode":1037,"ResultDesc":"No response from user."}}}
```

**No signature, no authentication of any kind** — the same exposure as MTN's callback,
already recorded in this repository. And **nothing the caller chose**: no `AccountReference`,
under that name or any other.

**A second run, 2026-09-22 ~10:35 UTC, confirms this shape rather than adding a new one.**
`CallBackURL` was `https://<tunnel-host>/callbacks/mpesa/b09bc5ff-9217-4517-9432-0648a45de77b`
— deliberately the per-payment shape issue #185 makes this gateway compose. Measured the same
way this page already measures it — *`CheckoutRequestID` encodes Nairobi local time*, above:
`ws_CO_220920261335362708374149` reads `13:35:36` EAT, `10:35:36Z` — the callback, logged at
`10:36:02.68Z`, arrived **26 seconds** later. That is September's own number again, not a
second one: two runs four days apart, same latency, measured the same way. The rest of this
callback matches the exact path supplied, extra segment intact. The body was the same shape
again: `MerchantRequestID`,
`CheckoutRequestID`, `ResultCode: 1037`, `ResultDesc: "No response from user."`, nested under
`Body.stkCallback`, and again nothing the caller chose. The source was the same address as
September, `196.201.212.69`, `Cf-Ipcountry: KE` — two samples of one value now, still not
enough to treat as an allow-list anyone could filter on. **New this time: the exact header
carrying the shortcode**, where September only noted that one arrived —
`Businessshortcode: 174379`, that capitalisation, as an HTTP header rather than a body field.
Still no signature, no credential of any kind, on either run: anyone who learns the URL can
post to it.

## The finding this page exists for

`CONTRIBUTING.md`'s second ground rule is that a timeout is not a failure: an unanswered call
becomes `UNKNOWN`, and the reconciler resolves it. **For M-Pesa STK Push, as these calls
stand, it cannot.** A submission whose response is lost leaves Nkap with no
`CheckoutRequestID`; the query refuses the reference Nkap does hold; re-submitting creates a
second, independent payment (see *There is no idempotency* above); and the callback, which
does arrive, names only identifiers Nkap has never seen, so it cannot be attributed to
anything. The payment stays `UNKNOWN` until a person reads Safaricom's portal.

**Transaction Status does not soften this.** Safaricom's documented example request, read on
the portal 2026-09-18:

```json
{"Initiator":"testapiuser","SecurityCredential":"…","CommandID":"TransactionStatusQuery",
 "TransactionID":"NEF61H8J60","OriginalConversationID":"7071-4170-a0e5-8345632bad442144258",
 "PartyA":"600782","IdentifierType":"4","ResultURL":"…","QueueTimeOutURL":"…",
 "Remarks":"OK","Occasion":"OK"}
```

**Read from the example, not inferred:** the two search keys are `TransactionID` and
`OriginalConversationID`, and the request carries `ResultURL` and `QueueTimeOutURL`. Neither
`AccountReference` nor any other field STK Push lets the caller choose appears anywhere in it.
That alone settles the question: this API cannot be asked about a payment using anything Nkap
chose, because Nkap has no way to attach such a value to an STK payment in the first place.

**Inferred, and marked as such:** `OriginalConversationID`'s example value —
`7071-4170-a0e5-8345632bad442144258` — has the same four-hex-group shape as the
`MerchantRequestID` STK Push returns (`5dbd-4f93-a478-41cdaf2b9acd86873`), and the field is
named *Original*, not *Originator*. It most likely names the conversation as Safaricom
recorded it, arriving in the submit response — the response that can be lost. This was not
tested: confirming it needs the Security Credential and the asynchronous result rig, to
confirm something the field list above already settles.

**A second observation, about Nkap rather than about the answer:** `ResultURL` and
`QueueTimeOutURL` mean Transaction Status is **asynchronous** — the HTTP response is an
acknowledgement, and the result arrives by callback. M-Pesa's only *synchronous* query is
`stkpushquery`, the one that demands `CheckoutRequestID`. A gateway whose reconciler polls has
exactly one synchronous way to ask, and it is keyed by a value the operator chose.

`docs/providers/orange-money.md` already named the shape of this, about MTN rather than
Orange:

> That is a property of MTN, not of operators.

Two operators reviewed, two that contradict `ProviderAdapter.query(ReferenceId,
Capability.Operation)` — this is the second instance, and a different one: Orange's contract
needs an extra field `query` does not pass; M-Pesa's has no field to pass at all until a
response that can be lost supplies it.

## One way out — an idea, not a finding

`CallBackURL` is supplied **per submission**, and the path supplied was delivered intact. So a
gateway could put its own reference in the URL it asks the operator to call: the body names
nothing we chose, but the address does. **This is a design idea, not an observation**, and the
one observation it rests on — *the path we supplied arrived unchanged* — is the finding to
credit, not the idea itself.

Its immediate consequence, recorded here when it was still true: `nkap.public-base-url`,
shipped in 1.1.0 by issue #116, composed `<base>/callbacks/<providerId>` — one URL per
**provider**, which an operator needing one per **payment** was not served by. Issue #185
built the idea above: `nkap.public-base-url` now composes
`<base>/callbacks/<providerId>/<reference>`, one URL per **payment**, for every provider —
still with no M-Pesa adapter in this repository to hand it to, and still without the
question this page opens (*The finding this page exists for*, above) actually closed. What
issue #185 did not decide is answered by `CallbackEvent.unattributed(...)`'s own continued
existence: an operator that calls one registered endpoint with no path of the gateway's
choosing — `providerCallbackHost` with no per-submission `X-Callback-Url`, the way MTN's own
callback would work without issue #116 — still needs it, so both mechanisms exist for now,
per issue #185's own pull request.

**The shape works end to end against the real sandbox, run 2026-09-22:** the per-payment
`CallBackURL` above was accepted at submission and delivered on the exact path supplied,
extra segment intact — see *The callback* above. `docs/providers/mtn.md` records the
equivalent run against MTN's real sandbox the same day: two operators, one mechanism, both
observed, neither trimming or rejecting the longer path.

**Working end to end is not the same as closing the question this page opens, and two runs
the same morning show why — two failure modes, the same answer.**

**Run 1, 10:35 UTC.** The recorder answered the delivery at `10:36:02Z` with `500` — the
delivery *succeeded* and the receiver rejected it. Nothing further arrived by `11:07Z`,
thirty-one minutes.

**Run 2, 11:15:21 UTC.** Same submission shape, a fresh `CheckoutRequestID`
(`ws_CO_220920261415237708374149`) and a fresh per-payment path
(`/callbacks/mpesa/7128386a-0b54-4041-b289-22fb27d8d077`). This time the recorder itself was
stopped and the tunnel left open, so the delivery *failed* rather than being refused:
Cloudflare answered `502`, nothing reached an origin. The recorder was restarted a minute
later, accepting everything again. Nothing arrived by `11:59Z`, forty-four minutes.

**So: a Safaricom STK callback is delivered once. Neither an explicit rejection nor a failed
delivery brings it back.** MTN, for contrast, re-delivered a callback it had already been
given a `200` for, three minutes later (`docs/providers/mtn.md`, *Callbacks are retried*).

**One step in run 2 is inference, not observation, and is written as such.** Nothing recorded
the second callback actually being *sent* — the origin was down by design, so there was
nothing to capture it with. What was observed is that the payment reached its terminal
outcome: a status query against `ws_CO_220920261415237708374149`, made after the recorder was
back up, answered `HTTP 200`, `ResultCode 1037` — so a callback was due. The inference is that
Safaricom sent it around `11:15:47Z`, on the twenty-six-second latency run 1 measured (and
September, and the `CheckoutRequestID` decode above — the same number, three ways now). Read
that sentence as *due, not seen*.

Put this beside [ADR 0014](../adr/0014-resolvable-not-queryable.md), which admits an adapter
that resolves a lost submission by `CALLBACK` alone — M-Pesa is the operator that forced it.
The callback now carries the gateway's own reference in its URL, so it *can* be attributed
without anything the body carries; but these two runs say that single delivery is all there
is, whether the receiver refuses it outright or is not there to receive it at all. For MTN a
transient failure on the receiving end costs nothing — the operator's own retry covers it.
Here it turns a resolvable payment back into one only a person reading Safaricom's portal can
settle. The consequence for an adapter is concrete: **acknowledge first, process after.**
Answering `200` only once the payment is resolved — the ordering a careful implementer
usually reaches for — is exactly what this operator's behaviour, as observed twice now,
punishes.

**The limits of these runs, in the same breath rather than left for a reader to assume past
them:** two samples, sandbox, STK Push only, one path shape, and the timeout outcome only —
the sandbox payer never approves a payment, so nothing here says what a *successful*
payment's callback carries, or how a success is handled once acknowledged first. Two failure
modes were tried (an explicit `500`, a failed delivery); nothing says whether a different
non-2xx status, or a wait longer than three-quarters of an hour, would change the answer. The
relevant *Still unknown* entries below are narrowed by this, not closed.

## `ResultCode` is the contract; `ResultDesc` is not

For the same payment and the same `ResultCode 1037`, the callback and the status query
disagree on the prose describing it:

| Channel | `ResultDesc` |
| --- | --- |
| the callback | `No response from user.` |
| the status query | `DS timeout user cannot be reached.` |

Same operator, same outcome, same minute. `docs/providers/mtn.md` already records MTN's
version of this rule — map on the code, never parse the message — and this is the sharper
demonstration of it: an adapter mapping on `ResultDesc` would work on whichever channel it
was written against and silently misclassify the other. This is the single most directly
actionable thing either run produced, worth its own line rather than a clause inside the
paragraph above.

## Still unknown

Left open deliberately rather than guessed. Each is worth a pull request adding a line here.

- **What the callback-URL validator actually checks.** Three samples leave uppercase letters
  as the leading hypothesis for the one rejection seen, but no sample varied case alone while
  holding the subdomain's existence constant, or the reverse — see *The callback* above.
- **What a successful payment's callback carries** (`CallbackMetadata`, receipt number, payer
  MSISDN) — everything above is the timeout path, because the sandbox payer never answers.
- **What a genuinely in-flight query answers**, as opposed to one already concluded.
- **The complete `ResultCode` vocabulary**, and what an unrecognised one looks like. `1037`
  ("No response from user") is now confirmed across three observations on two days
  (2026-09-18's query; 2026-09-22's callback and its own status query) — but one member of a
  vocabulary is not the vocabulary. Every other code, and what an unrecognised one looks
  like, is unknown. See *`ResultCode` is the contract; `ResultDesc` is not*, above, for a
  second thing those three observations settle: the code is stable across channels, the
  prose describing it is not.
- **Whether a non-2xx answer, or a failed delivery, makes Safaricom retry the callback —
  narrowed, not answered.** The September run only had a `200` to look back on. Two runs on
  2026-09-22 tried both remaining shapes: an explicit `500` (nothing further in the following
  thirty-one minutes) and a delivery that failed outright, receiver unreachable (nothing
  further in the following forty-four minutes). Neither is retried, at least not within an
  hour. Still unknown: any other non-2xx status, and whether a wait longer than that hour
  would change the answer.
- **Disbursement (B2C)**, whose authentication is different again — the portal's *Test
  Credentials* page generates an encrypted *Security Credential* from an initiator password,
  nothing like STK Push's bearer token.
- **Which M-Pesa markets Daraja covers, and whether a second API family exists for the
  rest.** Trade press confirms Vodacom Tanzania opened a developer API of its own, separate
  from Daraja (see *Sources*); no Safaricom or Vodacom page was read to confirm its market
  scope or its real name — "M-Pesa Open API" is this page's own label, taken from
  `openapiportal.m-pesa.com`'s URL, not from a source that actually names it that. If a
  second API family really does sit under one commercial name, one adapter per operator
  (ADR 0009's shape) stops describing what is being built.

## Sources

The portal pages behind the sandbox account, read 2026-09-18, plus the runs themselves. No
third-party client or blog post is a source for anything stated as observed on this page.

The shortcode `174379` is not a third-party value: it appears in Safaricom's own documented
example request, the same example whose `Password` was decoded to confirm
`base64(BusinessShortCode + Passkey + Timestamp)` (see *Authentication and submission*
above).

The test MSISDN `254708374149` is a third-party starting point, and is said so here rather
than folded in as if this project had found it independently — Safaricom's own documented
example uses `254722000000` and `254722111111` instead. Every run above confirmed
`254708374149` against the real sandbox, so it stands as observed, sourced from where it was
first read.

Two statements above come from neither the portal nor a run, and are named here with a URL
rather than left to the body's own "Wikipedia's own M-Pesa article" or "trade press" to stand
for a citation:

- The country list in *Where M-Pesa exists* — Wikipedia, *M-Pesa*, read 2026-09-18:
  <https://en.wikipedia.org/wiki/M-Pesa>. Its own wording groups India, Romania and Albania as
  terminated and describes South Africa separately, as a market the service struggled in — the
  body above follows that grouping rather than the one this page first assumed.
- The Vodacom developer-API claim in the same section — trade press, not a Safaricom or
  Vodacom page: Telecompaper, *Vodacom Tanzania opens up M-Pesa API to developers*, read
  2026-09-18: <https://www.telecompaper.com/news/vodacom-tanzania-opens-up-m-pesa-api-to-developers--1356269>.
  Confirms a separate developer API exists for at least one Vodacom market. Does **not** use
  the name "M-Pesa Open API", confirm which markets it covers, or come from Safaricom or
  Vodacom themselves — `openapiportal.m-pesa.com`, the portal that name is guessed from, is a
  JavaScript application that returned no readable content to check either claim against.
