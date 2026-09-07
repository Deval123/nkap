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

## What is implemented today

This is the scaffold (issue #1). The only behaviour is the happy path: a request
resolves to `SUCCESSFUL` on its first query. Latency, timeouts, callbacks, status
flapping and token expiry are issues #2–#9, each added through the scenario mechanism
designed in issue #2.

| Method | Path | Behaviour |
| --- | --- | --- |
| `POST` | `/collection/token/` | Returns a bearer token: `access_token`, `token_type`, `expires_in` (3600). Always valid. |
| `POST` | `/collection/v1_0/requesttopay` | Reads `X-Reference-Id` (a client-supplied UUID, the idempotency key), records the request, returns **202 with an empty body**. A reused `X-Reference-Id` returns **409**; a missing or malformed one returns **400**. |
| `GET` | `/collection/v1_0/requesttopay/{referenceId}` | Returns `{"status": "..."}` — `PENDING`, `SUCCESSFUL` or `FAILED`. The first query moves a request to `SUCCESSFUL`. An unknown reference returns **404**. |

The `X-Reference-Id` is matched case-insensitively, as MTN's own API does.

### Example

```bash
REF=$(uuidgen)
curl -i -X POST http://localhost:8081/collection/v1_0/requesttopay \
  -H "X-Reference-Id: $REF" -H "Content-Type: application/json" \
  -d '{"amount":"5000","currency":"XAF","payer":{"partyIdType":"MSISDN","partyId":"237600000000"}}'
# 202, empty body

curl -s http://localhost:8081/collection/v1_0/requesttopay/$REF
# {"status":"SUCCESSFUL"}
```
