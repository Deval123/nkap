# simulator-mpesa-app

A fake M-Pesa operator that misbehaves on command: the simulator's core with M-Pesa's face,
as a runnable application and a published image,
[`ghcr.io/deval123/nkap-simulator-mpesa`](https://github.com/deval123/nkap/pkgs/container/nkap-simulator-mpesa).
[`simulator/`](../simulator/README.md) is the same thing with MTN's face.

## Read this before you run it anywhere reachable

**This is a test tool, and it hands out the credentials it is sent.** Its control plane,
`/_nkap`, is unauthenticated by design, so that a test in any language can drive it.
`GET /_nkap/received` reports what the last token request and the last submission carried: the
Consumer Key and Consumer Secret **in the clear**, and the `Password`, from which the passkey
can be read back. Anyone who can reach `/_nkap` can read them.

It is for tests and sandboxes only. Never point anything that holds real Safaricom credentials
at it, and do not expose `/_nkap` beyond the network your tests run on.
[`docs/security-notes.md`](../docs/security-notes.md) §3 says why this is accepted and what
follows from it.

## Running it

```bash
docker run -p 8082:8082 ghcr.io/deval123/nkap-simulator-mpesa
```

or, from a clone:

```bash
mvn -B -pl simulator-mpesa-app -am package -DskipTests
java -jar simulator-mpesa-app/target/nkap-simulator-mpesa-app-*-boot.jar
```

It listens on **port 8082**, not the MTN simulator's 8081, so the two run side by side with no
flag. State is held in memory only: every restart is a clean operator.

A scenario can be mounted instead of posted, exactly as for the MTN image, and is applied before
the first request (issue #99). No file there is not an error; a file that does not parse, or a
directory where the file should be, stops the simulator at startup:

```bash
docker run -p 8082:8082 -v ./scenario.json:/etc/nkap/scenario.json ghcr.io/deval123/nkap-simulator-mpesa
```

## What it plays, and what it does not

It plays **Safaricom Daraja's STK Push, collections only**:

| Method | Path | |
| --- | --- | --- |
| `GET` | `/oauth/v1/generate?grant_type=client_credentials` | HTTP Basic with a Consumer Key and Secret; answers a bearer token. |
| `POST` | `/mpesa/stkpush/v1/processrequest` | The submission, bearer-authenticated. |
| `POST` | `/mpesa/stkpushquery/v1/query` | The status query, by `CheckoutRequestID`, bearer-authenticated. |

and the callback to the submission's `CallBackURL`. Bearer tokens are checked only when the
declared configuration turns enforcement on (`"token": {"enforce": true}`), as for MTN.

It does **not**:

- **check a Consumer Key, or a `Password` against a passkey.** Any pair gets a token, and any
  `Password` is accepted. What Safaricom answers to a wrong one was never observed, so this
  simulator does not invent it (#246). `GET /_nkap/received` records what arrived so a test can
  check it itself, such as a rotated credential reaching the operator.
- **play B2C**, for the reason `MpesaAdapter` gives: its authentication differs in kind, and
  nothing about it has been observed.

What of the face is observed against Safaricom's sandbox and what is modelled is recorded in
[`docs/providers/m-pesa.md`](../docs/providers/m-pesa.md), and in the face's own javadoc
(`simulator-mpesa`). A difference between the simulator and that page is a bug in one of the
two, not in Safaricom.

## The control plane

The same routes as the MTN simulator's, under `/_nkap`: declare, read and reset scenarios
(`/_nkap/scenarios`), read one payment's state (`/_nkap/state/{CheckoutRequestID}`) and its
callback attempts (`/_nkap/callbacks/{CheckoutRequestID}`), count submissions
(`/_nkap/submissions`), and forget every payment (`DELETE /_nkap/state`).
[`simulator/README.md`](../simulator/README.md#the-control-plane) documents them.

Scenarios are written in M-Pesa's vocabulary. An `onQuery` entry names an `MpesaResult`
(`SUCCESS`, `STILL_PROCESSING`, `NO_RESPONSE_FROM_USER`, …) or `"error500": true`, and
`onSubmit` never declares `CONFLICT`, since M-Pesa refuses no repeated reference: a scenario
that does is refused when posted and stops the simulator when mounted. For example, a payment
that is still processing on the first query and then times out waiting for the user:

```bash
curl -s -X POST localhost:8082/_nkap/scenarios -H 'Content-Type: application/json' -d '{
  "rules": [{ "scenario": {
    "name": "user-never-answers",
    "onQuery": [{ "status": "STILL_PROCESSING" }, { "status": "NO_RESPONSE_FROM_USER" }]
  }}]
}'
```

One route is this face's own:

| Method | Path | |
| --- | --- | --- |
| `GET` | `/_nkap/received` | `{tokenRequests, lastTokenRequest, submissionRequests, lastSubmission}`: how many of each arrived since `DELETE /_nkap/state`, and the most recent of each, credentials in the clear. See the warning above. |

## Why this is its own image

One application context holds one face. Both faces on one classpath fail to start: the core's
`CallbackDispatcher` takes exactly one `CallbackBody`, and each face supplies one. So M-Pesa's
face is not a flag on the MTN image but an image of its own, assembled here the way `simulator/`
assembles MTN's.
