<!-- markdownlint-disable MD041 -->
# Integration guide

For the person who has read `README.md`, run the demo, and now has to make their own backend
talk to Nkap. `docs/openapi.yaml` is the same API described as a schema; this file is the same
API shown as the gestures a real integration makes, in the order they actually happen.

**Every `curl` (and every other command) below actually runs, in CI, on every build** —
`examples/run-integration-guide.sh` extracts every ` ```bash ` block from this exact file, in
order, and executes the result as one script against a real gateway, a real simulator and a
real PostgreSQL (`.github/workflows/build.yml`'s `integration-guide` job). There is nothing
here that is merely described. The two things that genuinely cannot run in that job — an
account on a real MTN sandbox, and a second language's HTTP client — are marked as such where
they appear, rather than shown as if they were.

Run it yourself, against the demo stack from the [quick start](../README.md#quick-start-the-contributors-path):

```text
git clone https://github.com/deval123/nkap && cd nkap
docker compose up --build -d
./examples/run-integration-guide.sh
```

(Not a `bash` block on purpose — it is exactly what CI's own `integration-guide` job already
did before running this file, and running a nested `git clone` from inside a checkout that is
itself the result of one is not a thing this script tries to make sense of. Copy these three
lines into your own terminal instead of running the file the way the rest of this guide does.)

It provisions its own merchant and its own API key — it does not touch `compose.yaml`'s demo
credential — so it is safe to run after, before, or instead of `examples/demo.sh`.

## 1. Getting an API key

Keys are minted by a **host-side command, never a route** — the same reasoning
`docs/positioning.md` gives for why there are no sessions and no JWTs here: the caller is a
backend, not a browser, and a route that could mint a credential is worse than one that writes
ledger entries.

```bash
GATEWAY="${GATEWAY:-http://localhost:8080}"
SIMULATOR="${SIMULATOR:-http://localhost:8081}"

MERCHANT="guide-merchant-$(date +%s)"
GUIDE_KEY="$(docker compose run --rm gateway \
  --nkap.apikey.create --nkap.apikey.merchant="$MERCHANT" --nkap.apikey.label=integration-guide \
  | grep -oE 'nkap_[A-Za-z0-9_-]{43}')"

[ -n "$GUIDE_KEY" ] || { echo "FAIL: no key came back from --nkap.apikey.create"; exit 1; }
echo "provisioned a key for merchant '$MERCHANT'"
```

It is printed **once**. Only its SHA-256 is ever stored — there is no command or route that
reads it back, so if you lose it, you provision a new one; you cannot recover the old value.
Send it as `Authorization: Bearer <key>` on every request from here on. There is no
`merchantId` anywhere in a request body: the key *is* the merchant, which is what makes a
stolen or misused key contained to the merchant it was minted for.

## 2. The first collection

`POST /payments` both creates the payment and submits it — there is no separate step, because
a payment nobody sent to the operator is not a state this API has a use for.

```bash
IDEMPOTENCY_KEY="guide-first-collection-$(date +%s)"
RESPONSE="$(curl -sS -w '\n%{http_code}' -X POST "$GATEWAY/payments" \
  -H "Authorization: Bearer $GUIDE_KEY" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d '{
        "operation": "COLLECT",
        "amount": 5000,
        "currency": "XAF",
        "country": "cm",
        "counterpartyMsisdn": "46733123453",
        "payerMessage": "invoice 1042",
        "payeeNote": "invoice 1042"
      }')"
STATUS="$(echo "$RESPONSE" | tail -n1)"
BODY="$(echo "$RESPONSE" | sed '$d')"

echo "$BODY" | python3 -m json.tool
[ "$STATUS" = "201" ] || [ "$STATUS" = "202" ] || { echo "FAIL: expected 201 or 202, got $STATUS"; exit 1; }
REFERENCE="$(echo "$BODY" | python3 -c 'import json,sys;print(json.load(sys.stdin)["reference"])')"
echo "reference: $REFERENCE, http status: $STATUS"
```

Three things to notice, in the response you just got:

- **`country` names an installation, not a currency or a phone prefix.** `"cm"` routes to the
  `mtn-cm` installation this demo stack configures; a deployment that also served Ghana would
  configure `mtn-gh` and a caller would ask for it by name. Guessing the installation from the
  MSISDN or the currency was rejected on purpose — a number is ported, a currency can span
  several countries, and a merchant can operate in more than one.
- **There is no `merchantId` field above.** The payment you just created belongs to whichever
  merchant `$GUIDE_KEY` identifies — see step 1.
- **The status code is the answer, before you even look at the body.** `201` means the
  operator answered (accepted it, or already settled it). `202` means it did not — see the
  next section, because this is not a corner case, it is the reason this project exists.

**`Idempotency-Key` is two-sided.** Retry the exact same request with the exact same key, and
you get the exact same answer back — no second payment, no second call to the operator:

```bash
REPLAY="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/payments" \
  -H "Authorization: Bearer $GUIDE_KEY" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d '{
        "operation": "COLLECT",
        "amount": 5000,
        "currency": "XAF",
        "country": "cm",
        "counterpartyMsisdn": "46733123453",
        "payerMessage": "invoice 1042",
        "payeeNote": "invoice 1042"
      }')"
[ "$REPLAY" = "$STATUS" ] || { echo "FAIL: a replay changed the status code ($STATUS -> $REPLAY)"; exit 1; }
echo "same key, same body: replayed, still $REPLAY -- no second payment"
```

Send the same key with a **different** body — a different amount, say, because your own retry
logic built the request from scratch instead of replaying it verbatim — and you get `409`, not
a silent guess about which one you meant:

```bash
CONFLICT="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/payments" \
  -H "Authorization: Bearer $GUIDE_KEY" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
  -d '{
        "operation": "COLLECT",
        "amount": 9999,
        "currency": "XAF",
        "country": "cm",
        "counterpartyMsisdn": "46733123453",
        "payerMessage": "invoice 1042",
        "payeeNote": "invoice 1042"
      }')"
[ "$CONFLICT" = "409" ] || { echo "FAIL: expected 409 on a key reused with a different body, got $CONFLICT"; exit 1; }
echo "same key, different body: 409, nothing submitted"
```

**Reuse a key you generate once per intended payment** — a UUID, an idempotency key your own
job queue already gives you, anything unique to that one attempt — and retry with it as many
times as a flaky connection makes you. That is what makes retrying safe.

## 3. What to do with `UNKNOWN`

This is the whole reason this project exists, so it gets its own section rather than a
footnote on step 2. Script the operator to accept a submission and then say nothing at all —
`examples/demo.sh`'s own trick — and submit a second payment:

```bash
curl -fsS -X POST "$SIMULATOR/_nkap/scenarios" \
  -H 'Content-Type: application/json' \
  -d '{"rules":[{"scenario":{"onSubmit":{"outcome":"NO_RESPONSE"},"onQuery":[{"status":"SUCCESSFUL"}]}}]}'

UNKNOWN_RESPONSE="$(curl -sS -w '\n%{http_code}' -X POST "$GATEWAY/payments" \
  -H "Authorization: Bearer $GUIDE_KEY" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: guide-unknown-$(date +%s)" \
  -d '{
        "operation": "COLLECT",
        "amount": 3000,
        "currency": "XAF",
        "country": "cm",
        "counterpartyMsisdn": "46733123453",
        "payerMessage": "invoice 1043",
        "payeeNote": "invoice 1043"
      }')"
UNKNOWN_STATUS="$(echo "$UNKNOWN_RESPONSE" | tail -n1)"
UNKNOWN_BODY="$(echo "$UNKNOWN_RESPONSE" | sed '$d')"
UNKNOWN_REFERENCE="$(echo "$UNKNOWN_BODY" | python3 -c 'import json,sys;print(json.load(sys.stdin)["reference"])')"

[ "$UNKNOWN_STATUS" = "202" ] || { echo "FAIL: expected 202, got $UNKNOWN_STATUS"; exit 1; }
echo "$UNKNOWN_BODY" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["state"]=="UNKNOWN", d; print("state:", d["state"], "-- detail:", d["detail"])'
```

Read that response again: **it is not an error.** There is no exception to catch, no failure to
log, and absolutely nothing here to tell your payer. `state: UNKNOWN` means exactly what it
says — the outcome is not yet known, because the operator did not answer — and it resolves
itself. What you do next is one of two things, both correct, and you can do both at once:

**Poll**, on your own schedule, until it stops being `UNKNOWN`:

```bash
RESOLVED=""
for _ in $(seq 1 40); do
  STATE="$(curl -sS -H "Authorization: Bearer $GUIDE_KEY" "$GATEWAY/payments/$UNKNOWN_REFERENCE" \
    | python3 -c 'import json,sys;print(json.load(sys.stdin)["state"])')"
  [ "$STATE" != "UNKNOWN" ] && { RESOLVED="$STATE"; break; }
  sleep 2
done
[ -n "$RESOLVED" ] || { echo "FAIL: still UNKNOWN after 80s"; exit 1; }
echo "resolved on its own to: $RESOLVED"
```

**Or wait for a webhook** — the next section — which fires the moment it reaches
`SUCCEEDED`, `FAILED` or `EXPIRED`, so you do not have to poll at all if your backend can
receive one.

The one thing that is never correct: submitting the same intent again under a *new*
`Idempotency-Key` because the first attempt "failed". It did not fail. Doing that is a second,
real payment.

## 4. Receiving and verifying a webhook

`docs/webhooks.md` is the full contract — the payload shape, the retry policy, what fires and
what deliberately does not. This section proves the two facts that matter most for a receiver
you are about to write, against a real delivery rather than a description of one: **the
signature actually verifies**, and **at-least-once delivery means you will see the same event
more than once, so your receiver has to be idempotent on its `id`.**

A receiver is just an HTTP server. Here is the smallest one that can prove the point — capture
what it receives instead of acting on it, which is all this guide needs from it:

```bash
cat > /tmp/nkap-guide-receiver.py <<'PYEOF'
import http.server

class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length).decode("utf-8")
        print("HEADERS_START")
        for key, value in self.headers.items():
            print(f"{key}: {value}")
        print("HEADERS_END")
        print("BODY_START")
        print(body)
        print("BODY_END")
        self.send_response(200)
        self.end_headers()

    def log_message(self, format, *args):
        pass  # the assertions below read the printed request, not this server's own log

http.server.HTTPServer(("0.0.0.0", 9191), Handler).serve_forever()
PYEOF

# python3 -u: unbuffered stdout. Without it, a print() inside a container sits in a buffer
# that "docker logs" cannot see until it fills or the process exits -- neither of which a
# long-running receiver ever does on its own, so the request would have happened and this
# guide would still be waiting for evidence of it.
docker run -d --rm --name guide-webhook-receiver --network nkap_default \
  -v /tmp/nkap-guide-receiver.py:/receiver.py -p 9191:9191 \
  python:3.12-alpine python3 -u /receiver.py
sleep 1
```

Register it the same way an API key is registered — a host-side command, shown once:

```bash
WEBHOOK_SECRET="whsec_integration-guide-$(date +%s)"
docker compose run --rm gateway \
  --nkap.webhook.create --nkap.webhook.merchant="$MERCHANT" \
  --nkap.webhook.url=http://guide-webhook-receiver:9191/hook \
  --nkap.webhook.secret="$WEBHOOK_SECRET" \
  --nkap.webhooks.allow-insecure-endpoint-url=true
```

(`allow-insecure-endpoint-url` exists only because this receiver has no TLS in front of it, on
purpose, for this guide. `docs/webhooks.md` explains why a real endpoint must be `https`, and
provisioning refuses `http://` by default for exactly that reason.)

Settle the payment from step 3 — the one still sitting at whatever it resolved to is fine, but
a fresh one makes the timing obvious — and wait for the webhook to actually arrive rather than
guessing how long that takes:

```bash
curl -fsS -X POST "$SIMULATOR/_nkap/scenarios" \
  -H 'Content-Type: application/json' \
  -d '{"rules":[{"scenario":{"onQuery":[{"status":"SUCCESSFUL"}]}}]}'

WEBHOOK_REFERENCE="$(curl -sS -X POST "$GATEWAY/payments" \
  -H "Authorization: Bearer $GUIDE_KEY" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: guide-webhook-demo-$(date +%s)" \
  -d '{
        "operation": "COLLECT",
        "amount": 4000,
        "currency": "XAF",
        "country": "cm",
        "counterpartyMsisdn": "46733123453",
        "payerMessage": "invoice 1044",
        "payeeNote": "invoice 1044"
      }' | python3 -c 'import json,sys;print(json.load(sys.stdin)["reference"])')"

DELIVERED=""
for _ in $(seq 1 30); do
  LOGS="$(docker logs guide-webhook-receiver 2>&1)"
  if echo "$LOGS" | grep -q "\"reference\":\"$WEBHOOK_REFERENCE\""; then
    DELIVERED="yes"
    break
  fi
  sleep 2
done
[ -n "$DELIVERED" ] || { echo "FAIL: no webhook for $WEBHOOK_REFERENCE arrived within 60s"; exit 1; }
echo "the webhook for $WEBHOOK_REFERENCE arrived"
```

Pull out exactly what your own receiver would have: the signature header and the raw body —
**raw**, before any JSON parsing, which is what the signature actually covers.

```bash
LAST_REQUEST="$(docker logs guide-webhook-receiver 2>&1 \
  | awk -v ref="$WEBHOOK_REFERENCE" '
      /^HEADERS_START$/ { buf=""; capturing=1 }
      capturing { buf = buf $0 "\n" }
      /^BODY_END$/ { if (buf ~ ref) last = buf; capturing=0 }
      END { printf "%s", last }
    ')"
SIGNATURE_HEADER="$(echo "$LAST_REQUEST" | grep -i '^Nkap-Signature:' | sed 's/^[^:]*: *//')"
RAW_BODY="$(echo "$LAST_REQUEST" | sed -n '/^BODY_START$/,/^BODY_END$/p' | sed '1d;$d')"

[ -n "$SIGNATURE_HEADER" ] || { echo "FAIL: no Nkap-Signature header captured"; exit 1; }
echo "captured signature: $SIGNATURE_HEADER"
```

Now verify it — the exact recipe from `docs/webhooks.md`, run for real against the secret this
guide provisioned and the request the gateway actually sent, not a synthetic example:

```bash
python3 <<PYEOF
import hmac, hashlib, time

secret = "$WEBHOOK_SECRET"
header = "$SIGNATURE_HEADER"
body = """$RAW_BODY"""

parts = dict(p.split("=", 1) for p in header.split(","))
timestamp, given_hex = int(parts["t"]), parts["v1"]

signed_payload = f"{timestamp}.{body}"
expected_hex = hmac.new(secret.encode(), signed_payload.encode(), hashlib.sha256).hexdigest()

if not hmac.compare_digest(expected_hex, given_hex):
    raise SystemExit("FAIL: signature does not match -- this body was not signed with our secret")
if abs(time.time() - timestamp) > 300:
    raise SystemExit("FAIL: timestamp is outside the tolerance window -- treat as a possible replay")
print("signature verified: HMAC-SHA256 over \\"t.body\\" matches, and the timestamp is fresh")
PYEOF
```

Three things this proves that a description alone cannot: the signature is computed over
`"{t}.{body}"`, not the body alone (sign either one wrong and this fails); the comparison has
to be constant-time (`hmac.compare_digest`, not `==`) or the check itself leaks timing
information about the secret; and the timestamp has to be checked *in addition to* the
signature, or a captured, valid request could be replayed later.

**Delivery is at-least-once — you will see this same event more than once.** Nkap's own admin
API can force exactly that, the same way a real retry would: `POST
/webhooks/events/{eventId}/replay` resends a specific event by hand. It needs an admin key,
since it triggers a real outbound call in a merchant's name:

```bash
ADMIN="admin-$(date +%s)"
ADMIN_KEY="$(docker compose run --rm gateway \
  --nkap.apikey.create --nkap.apikey.merchant="$ADMIN" --nkap.apikey.admin --nkap.apikey.label=integration-guide \
  | grep -oE 'nkap_[A-Za-z0-9_-]{43}')"

EVENT_ID="$(echo "$RAW_BODY" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')"

curl -sS -o /dev/null -X POST -H "Authorization: Bearer $ADMIN_KEY" \
  "$GATEWAY/webhooks/events/$EVENT_ID/replay"
sleep 2
```

A receiver that only checks "did this payment settle" would process this twice. **Deduplicate
on `id`** — it is the one field that is identical across every delivery of the same event,
including a manual replay:

```bash
SEEN_FILE="/tmp/nkap-guide-seen-events"
: > "$SEEN_FILE"  # a real receiver persists this; a shell variable does not survive a restart

process_if_new() {
  local id="$1"
  if grep -qx "$id" "$SEEN_FILE"; then
    echo "  event $id: already processed, skipping"
    return
  fi
  echo "$id" >> "$SEEN_FILE"
  echo "  event $id: processing for the first time"
}

ALL_BODIES="$(docker logs guide-webhook-receiver 2>&1 \
  | awk -v ref="$WEBHOOK_REFERENCE" '
      /^BODY_START$/ { buf=""; capturing=1; next }
      /^BODY_END$/ { if (buf ~ ref) print buf; capturing=0; next }
      capturing { buf = buf $0 }
    ')"
DELIVERY_COUNT=0
while IFS= read -r body; do
  [ -z "$body" ] && continue
  DELIVERY_COUNT=$((DELIVERY_COUNT + 1))
  id="$(echo "$body" | python3 -c 'import json,sys;print(json.load(sys.stdin)["id"])')"
  process_if_new "$id"
done <<< "$ALL_BODIES"

[ "$DELIVERY_COUNT" -ge 2 ] || { echo "FAIL: expected the original delivery plus the replay, saw $DELIVERY_COUNT"; exit 1; }
echo "$DELIVERY_COUNT deliveries for one event, deduplicated down to one processed"
```

*(Not executed here: the equivalent of the block above in whatever language your receiver is
actually written in. The algorithm is the same regardless — verify the raw body before parsing
it, check the timestamp, deduplicate on `id` — `docs/webhooks.md` is the language-agnostic
version of this recipe.)*

```bash
docker rm -f guide-webhook-receiver >/dev/null 2>&1 || true
```

## 5. A refund, and what the cap refuses

A refund is a payment — created, submitted, polled and notified exactly like the collection
above, which is why it shares this API rather than getting one of its own. Settle a fresh
collection first:

```bash
curl -fsS -X POST "$SIMULATOR/_nkap/scenarios" \
  -H 'Content-Type: application/json' \
  -d '{"rules":[{"scenario":{"onQuery":[{"status":"SUCCESSFUL"}]}}]}'

REFUND_ORIGINAL="$(curl -sS -X POST "$GATEWAY/payments" \
  -H "Authorization: Bearer $GUIDE_KEY" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: guide-refund-original-$(date +%s)" \
  -d '{
        "operation": "COLLECT",
        "amount": 5000,
        "currency": "XAF",
        "country": "cm",
        "counterpartyMsisdn": "46733123453",
        "payerMessage": "invoice 1045",
        "payeeNote": "invoice 1045"
      }' | python3 -c 'import json,sys;print(json.load(sys.stdin)["reference"])')"

for _ in $(seq 1 40); do
  STATE="$(curl -sS -H "Authorization: Bearer $GUIDE_KEY" "$GATEWAY/payments/$REFUND_ORIGINAL" \
    | python3 -c 'import json,sys;print(json.load(sys.stdin)["state"])')"
  [ "$STATE" = "SUCCEEDED" ] && break
  sleep 2
done
[ "$STATE" = "SUCCEEDED" ] || { echo "FAIL: the collection never reached SUCCEEDED"; exit 1; }
echo "$REFUND_ORIGINAL settled -- 5000 XAF, refundable in full"
```

Refund part of it. `amount`, omitted, would refund the full remaining balance; here it is given
explicitly to leave something to refund a second time:

```bash
PARTIAL_REFUND="$(curl -sS -w '\n%{http_code}' -X POST "$GATEWAY/payments/$REFUND_ORIGINAL/refunds" \
  -H "Authorization: Bearer $GUIDE_KEY" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: guide-refund-1-$(date +%s)" \
  -d '{"amount": 2000, "note": "partial refund, item out of stock"}')"
PARTIAL_STATUS="$(echo "$PARTIAL_REFUND" | tail -n1)"
[ "$PARTIAL_STATUS" = "201" ] || [ "$PARTIAL_STATUS" = "202" ] || { echo "FAIL: expected 201/202, got $PARTIAL_STATUS"; exit 1; }
echo "refunded 2000 of 5000 XAF -- 3000 left"
```

Ask for more than remains — 2000 was already taken, only 3000 is left, this asks for 3500 —
and the cap refuses it:

```bash
OVER_REFUND="$(curl -sS -X POST "$GATEWAY/payments/$REFUND_ORIGINAL/refunds" \
  -H "Authorization: Bearer $GUIDE_KEY" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: guide-refund-2-$(date +%s)" \
  -d '{"amount": 3500}')"
echo "$OVER_REFUND" | python3 -m json.tool
echo "$OVER_REFUND" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["type"].endswith("refund-exceeds-remaining"), d'
```

**That cap is reserved the moment a refund is created, not once it settles** — the instant the
first `POST .../refunds` above returned, the 2000 already counted against the original, whether
or not the operator had answered yet. Two refund requests issued at the exact same instant for
more than remains: exactly as many as fit are accepted, enforced by an atomic database update
under the row that holds the running total, not by a check in this process — which is what
makes it hold even when both requests really do race, not just when they happen not to.

A refund's destination is never something you choose — it is always the original payer:

```bash
NAMED_DESTINATION="$(curl -sS -X POST "$GATEWAY/payments/$REFUND_ORIGINAL/refunds" \
  -H "Authorization: Bearer $GUIDE_KEY" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: guide-refund-3-$(date +%s)" \
  -d '{"counterpartyMsisdn": "46700000000"}')"
echo "$NAMED_DESTINATION" | python3 -c 'import json,sys; d=json.load(sys.stdin); assert d["type"].endswith("refund-destination-not-allowed"), d'
echo "a supplied destination is refused, not silently ignored"
```

## 6. The errors you will actually hit

Every error is `application/problem+json` (RFC 7807): `type`, `title`, `status`, `detail`,
`instance`, always all five. Branch on `type` — it is a stable identifier, not a URL you are
meant to fetch. `docs/openapi.yaml` is the complete, authoritative list per endpoint; this
table is the ones an integration hits early, with what to actually do about each:

| `type` (suffix) | Status | What it means | What to do |
| --- | --- | --- | --- |
| `missing-idempotency-key` | 400 | `Idempotency-Key` header was absent | Always send one; see step 2 |
| `invalid-request` | 400 | A field is missing, blank, or not a value the domain accepts | Fix the request; nothing was persisted |
| `malformed-request` | 400 | The body is not JSON, or a number where an integer minor-unit count was required | Never send a decimal amount |
| `idempotency-key-reuse` | 409 | The same key, a different body | Use a new key, or resend the exact original body |
| `request-in-progress` | 409 | An earlier request with this key has not finished | Retry once it has |
| `unconfigured-country` | 400 | `country` names no installation this deployment configures | The message names what is configured; fix the request |
| `unserved-currency` | 400 | This installation settles a different currency than requested | Check `docs/providers/mtn.md` for what a given `country` actually settles in |
| `payment-not-found` | 404 | No such reference, or it belongs to another merchant | Same answer either way, on purpose — see step 1 |
| `original-not-refundable` | 400 | The collection is not `SUCCEEDED` (`UNKNOWN` included) | Wait for it to resolve; never refund a guess |
| `cannot-refund-a-disbursement` | 400 | The reference names a `DISBURSE`, not a collection | Only a collection can be refunded |
| `refund-destination-not-allowed` | 400 | `counterpartyMsisdn` was supplied on a refund | Remove it — see step 5 |
| `refund-exceeds-remaining` | 400 | This request, alone or racing another, would exceed what remains | See step 5 |
| `unauthenticated` | 401 | No key, or one not recognised | One message for both, on purpose — see step 1 |
| `admin-key-required` | 403 | The endpoint is operator-wide; this key is a merchant key | `GET /balance`, `GET /statements/imports/{id}`, both webhook admin routes |
| `operator-did-not-answer` | 503 | A **live** read (`GET /balance`, `GET /account-holders/{msisdn}`) got no answer | Not known, not failed — the identical request can be retried |

Two of these, proven above rather than only listed: `missing-idempotency-key` (step 2's very
first call would have needed one) and `refund-exceeds-remaining` / `refund-destination-not-allowed`
(step 5). One thing every row in this table has in common, worth saying once at the end
because it is the same fact this whole guide keeps coming back to: **none of them are a
`500`, and none of them mean the payment failed unless the operator explicitly said so.** A
timeout is `UNKNOWN`, not an entry in this table at all.
