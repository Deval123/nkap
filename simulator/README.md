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
| `POST` | `/collection/token/` | Returns a bearer token: `access_token`, `token_type`, `expires_in`. The lifetime comes from the scenario of the most recent submission (`token.ttl`), and is 3600 seconds by default. |
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
- `callbacks`: modelled (`{after, times, target, status}`) but **not delivered yet** —
  the dispatcher is issues #5 to #7.
- `token`: a `ttl`.

Every field is optional and defaults sensibly: an empty document is the happy path
(accepted on submission, `SUCCESSFUL` on the next query, a one-hour token). A scenario
file declares only what it changes.

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
| `POST` | `/_nkap/scenarios` | Replace the rule list. Body `{"rules":[...]}`. **204**. A malformed scenario is a **400** whose body names the offending field. |
| `GET` | `/_nkap/scenarios` | Return the current rule list. |
| `DELETE` | `/_nkap/scenarios` | Back to the happy path only. **204**. |
| `GET` | `/_nkap/state/{referenceId}` | `{scenario, queryCount, submittedAt}`, or **404**. |
| `DELETE` | `/_nkap/state` | Forget every reference, so a test suite's cases do not leak into one another. **204**. |

### A flapping scenario, end to end

Make every request from one MSISDN report `SUCCESSFUL` once and then `FAILED` for good:

```bash
curl -s -X POST localhost:8081/_nkap/scenarios \
  -H 'Content-Type: application/json' -d '{
  "rules": [
    {
      "match": { "msisdn": "237600000001" },
      "scenario": {
        "name": "flapping",
        "onSubmit": { "delay": "PT0S", "outcome": "ACCEPT" },
        "onQuery": [
          { "status": "SUCCESSFUL" },
          { "status": "FAILED", "reason": "PAYER_LIMIT_REACHED" }
        ],
        "token": { "ttl": "PT1H" }
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

## What is not here yet

Callbacks are modelled but not delivered (issues #5–#7). The individual scenario files
of issues #3–#10 are configuration written against this mechanism, one per pull request.
Loading scenarios from YAML is issue #10.
