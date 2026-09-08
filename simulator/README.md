# simulator

A fake mobile money operator that misbehaves on command.

It exposes the MTN MoMo API surface and plays a declared scenario: latency, timeout then
late success, a callback delivered twice, a callback delivered before the submit
response, a status that flips from `SUCCESSFUL` to `FAILED`, a token that expires
mid-flight.

It serves the project's own test suite first. But it has a strategic property: **it is
useful on its own.** A developer who will never run Nkap still wants a reliable fake MoMo
to test their integration against. It is therefore the front door of this project — and
each new scenario is an isolated, testable, low-risk pull request.

This is why the simulator is built *first*, not last.

## Running it

```bash
mvn -B -pl simulator spring-boot:run
```

It listens on **port 8081** (`server.port` in `simulator/src/main/resources/application.yml`).
State is held in memory only — every restart is a clean operator.

## The operator surface

| Method | Path | Behaviour |
| --- | --- | --- |
| `POST` | `/collection/token/` | Returns a bearer token: `access_token`, `token_type`, `expires_in`. The lifetime is the one declared with the rule set (`token.ttl`), and is 3600 seconds by default. |
| `POST` | `/collection/v1_0/requesttopay` | Reads `X-Reference-Id` (a client-supplied UUID, the idempotency key), records the request, and applies the resolved scenario's `onSubmit`. Default is **202 with an empty body**. A reused `X-Reference-Id` is **409**; a missing or malformed one is **400** — those are protocol errors and keep priority over the scenario. |
| `GET` | `/collection/v1_0/requesttopay/{referenceId}` | Applies the scenario's next `onQuery` entry and returns `{"status": "..."}` (plus `reason` when the scenario sets one). The default scenario moves a request to `SUCCESSFUL` on the first query. An unknown reference is **404**. |

The `X-Reference-Id` is matched case-insensitively, as MTN's own API does.

## The scenario mechanism

A scenario is a **timeline** — what the simulator does at each interaction point of one
payment (see `docs/adr/0002-scenarios-are-timelines.md`):

- `onSubmit`: a `delay` and an `outcome` among `ACCEPT`, `CONFLICT`, `BAD_REQUEST`,
  `SERVER_ERROR`, `NO_RESPONSE` (`NO_RESPONSE` never answers — it is the timeout case).
- `onQuery`: an ordered list of `{delay, status, reason}`, one per successive query.
  **The last entry repeats**, so a client that polls more times than the scenario
  declares keeps getting a defined answer.
- `callbacks`: a list of `{after, every, times, target, status}`. Each is delivered
  `times` times — the first `after` the submission, the rest `every` apart — for either
  the request's own reference (`SAME_REFERENCE`) or one it never submitted
  (`UNKNOWN_REFERENCE`). See [Callbacks](#callbacks).

Every field is optional and defaults sensibly: an empty document is the happy path
(accepted on submission, `SUCCESSFUL` on the next query). A scenario file declares only
what it changes.

The **token lifetime and the fallback callback URL are not part of a scenario**. A bearer
token is obtained before any payment exists, and a callback URL is about where the
simulator reaches you, not about one payment's timeline. Both are declared in the same
document as the rules (`"token": {"ttl": "..."}`, `"callbackUrl": "..."`), and one `POST`
replaces the whole configuration at once.

Scenarios are chosen by **ordered rules**. Each rule pairs a matcher — on `referenceId`,
`msisdn`, `amount` or `currency`, any subset, a field left out is not compared — with a
scenario. The first matching rule wins; a rule with no matcher matches every request; no
match means the happy path. **The scenario is resolved once, at submission, and frozen
against the reference**: replacing the rules does not change how an in-flight payment
behaves.

Durations are ISO-8601 strings: `"PT2S"`, `"PT0S"`, `"PT1H"`.

## The control plane

Namespaced under `/_nkap/` so it can never collide with an operator path. **The simulator
is driven entirely over HTTP, so it is usable from a test suite in any language** — no
Java fixture, no recompilation.

| Method | Path | Effect |
| --- | --- | --- |
| `POST` | `/_nkap/scenarios` | Replace the whole configuration. Body `{"token":{"ttl":"..."},"callbackUrl":"...","rules":[...]}` — all optional. **204**. A malformed scenario is a **400** whose body names the offending field. |
| `GET` | `/_nkap/scenarios` | Return the current `{token, callbackUrl, rules}`. |
| `DELETE` | `/_nkap/scenarios` | Back to the happy path, a one-hour token and no callback URL. **204**. |
| `GET` | `/_nkap/state/{referenceId}` | `{scenario, queryCount, submittedAt}`, or **404**. |
| `GET` | `/_nkap/callbacks/{referenceId}` | The callback delivery attempts for that reference, oldest first — `[]` if none. |
| `DELETE` | `/_nkap/state` | Forget every reference and its callback attempts, so a test suite's cases do not leak into one another. Leaves the declared configuration alone. **204**. |

### A flapping scenario, end to end

Make every request from one MSISDN report `SUCCESSFUL` once and then `FAILED` for good:

```bash
curl -s -X POST localhost:8081/_nkap/scenarios \
  -H 'Content-Type: application/json' -d '{
  "token": { "ttl": "PT1H" },
  "rules": [
    {
      "match": { "msisdn": "237600000001" },
      "scenario": {
        "name": "flapping",
        "onSubmit": { "delay": "PT0S", "outcome": "ACCEPT" },
        "onQuery": [
          { "status": "SUCCESSFUL" },
          { "status": "FAILED", "reason": "PAYER_LIMIT_REACHED" }
        ]
      }
    }
  ]
}'

REF=$(uuidgen)
curl -s -X POST localhost:8081/collection/v1_0/requesttopay \
  -H "X-Reference-Id: $REF" -H 'Content-Type: application/json' \
  -d '{"amount":"5000","currency":"XAF","payer":{"partyIdType":"MSISDN","partyId":"237600000001"}}'

curl -s localhost:8081/collection/v1_0/requesttopay/$REF   # {"status":"SUCCESSFUL"}
curl -s localhost:8081/collection/v1_0/requesttopay/$REF   # {"status":"FAILED","reason":"PAYER_LIMIT_REACHED"}
curl -s localhost:8081/collection/v1_0/requesttopay/$REF   # {"status":"FAILED","reason":"PAYER_LIMIT_REACHED"}

curl -s -X DELETE localhost:8081/_nkap/scenarios           # back to the happy path
```

### Timeout, then late success

The scenario every integrator should try first (issue #3): the submit call never comes
back, and the payment nonetheless succeeds on the next query. It is `NO_RESPONSE` on
submit and `SUCCESSFUL` on query — no special case, just the timeline.

```bash
curl -s -X POST localhost:8081/_nkap/scenarios -H 'Content-Type: application/json' -d '{
  "rules": [{
    "match": {},
    "scenario": {
      "name": "timeout-then-late-success",
      "onSubmit": { "outcome": "NO_RESPONSE" },
      "onQuery": [{ "status": "SUCCESSFUL" }]
    }
  }]
}'

REF=$(uuidgen)
# Submit with a short client timeout: it will trip, the operator never answers.
curl -s -m 2 -X POST localhost:8081/collection/v1_0/requesttopay \
  -H "X-Reference-Id: $REF" -H 'Content-Type: application/json' \
  -d '{"amount":"5000","currency":"XAF","payer":{"partyIdType":"MSISDN","partyId":"237600000000"}}'
# (curl: (28) Operation timed out)

curl -s localhost:8081/collection/v1_0/requesttopay/$REF   # {"status":"SUCCESSFUL"}
```

## Callbacks

A scenario's `callbacks` are delivered as real outbound `POST`s.

**Where.** In order of precedence: the `X-Callback-Url` header on the submit request (as
MTN does); failing that, a `callbackUrl` declared in the control plane alongside the rules;
with neither, nothing is sent — that is not an error, most scenarios have no callbacks, so
it is logged at INFO and no louder.

**When.** Callbacks are scheduled **the moment the scenario resolves, before the submit
delay is applied.** That is what lets a callback with `after: PT0S` reach the client while
its submit call is still blocked — issue #6, the callback before the submit response.

**What.** A body shaped like MTN's: `referenceId`, `status`, `amount`, `currency`,
`financialTransactionId`, and `reason` when the status is `FAILED`. No retries — the
simulator sends exactly what the scenario declares; a failed delivery is recorded and
logged, nothing more.

**Proof without a receiver.** `GET /_nkap/callbacks/{referenceId}` returns every attempt —
when, to which URL, for which reference, with which status, whether the receiver answered.
A test can assert that two callbacks went out two seconds apart without standing up a
server to catch them, and therefore from a suite written in any language.

```bash
# a callback delivered twice, two seconds apart
curl -s -X POST localhost:8081/_nkap/scenarios -H 'Content-Type: application/json' -d '{
  "rules": [{
    "match": {},
    "scenario": {
      "name": "duplicate-callback",
      "callbacks": [{ "after": "PT0S", "every": "PT2S", "times": 2, "status": "SUCCESSFUL" }]
    }
  }]
}'

REF=$(uuidgen)
curl -s -X POST localhost:8081/collection/v1_0/requesttopay \
  -H "X-Reference-Id: $REF" -H "X-Callback-Url: http://localhost:9999/hook" \
  -H 'Content-Type: application/json' \
  -d '{"amount":"5000","currency":"XAF","payer":{"partyIdType":"MSISDN","partyId":"237600000000"}}'

sleep 3
curl -s localhost:8081/_nkap/callbacks/$REF   # two attempts, ~2s apart
```

## Two things to know before you write a scenario

**Scenario delays block a request thread.** `onSubmit.delay` and `onQuery.delay` are
applied with a plain sleep on the handler thread. That is fine because they are meant to
be **seconds, not minutes** — long enough to trip a client's timeout, short enough that a
handful of them in parallel will not drain the servlet pool. The latency scenario of
issue #3 should stay well under about ten seconds; a minutes-long delay belongs to
`NO_RESPONSE`, which never answers and holds no thread.

**A simulated `SERVER_ERROR` or `BAD_REQUEST` still consumes the reference.** The
`X-Reference-Id` is recorded before the scenario is consulted, so a client that retries
after a simulated 500 gets **409 Conflict**, not the 500 again. This is deliberate — the
reference really was used, and MTN would say the same — but retry-after-5xx is exactly
the path people come to the simulator to exercise, so it is called out here rather than
left to be discovered. To replay the same reference, `DELETE /_nkap/state` first.

## What is not here yet

The individual scenario files of issues #4–#9 are configuration written against this
mechanism, one per pull request. Loading scenarios from YAML is issue #10. Enforcing token
expiry mid-flight (rejecting an expired token) is issue #9 — the lifetime is declared and
reported, but nothing checks it yet.
