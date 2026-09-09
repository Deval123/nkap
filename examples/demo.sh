#!/usr/bin/env bash
#
# The argument of the whole project, in one run against the live stack from `compose.yaml`.
#
#   1. script the operator to accept the submission and then go silent — no callback
#   2. POST /payments  -> 202, and the payment is UNKNOWN (not FAILED)
#   3. GET  /payments/{ref} says UNKNOWN, and the ledger has not moved
#   4. wait: the reconciler re-queries the operator — its first re-query still fails, the next
#      succeeds — and settles the payment
#   5. SUCCEEDED, and the transition into it is attributed to RECONCILER; one ledger entry,
#      two postings, summing to zero
#
# There is deliberately no callback in the scenario. CallbackApiIT already proves that path;
# with it gone, only the reconciler — the thing this project is built on — can resolve the
# payment, so this run proves the reconciler and not a webhook.
#
# Every step is asserted. The script exits non-zero the moment one does not hold — which
# is what lets CI run it as a test. Run it from anywhere after `docker compose up`:
#
#   docker compose up --build -d
#   ./examples/demo.sh
#
set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
SIMULATOR="${SIMULATOR:-http://localhost:8081}"

# Run compose commands from the repository root, whatever directory we were invoked from.
cd "$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"

say()  { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }
ok()   { printf '   \033[32m✓\033[0m %s\n' "$*"; }
info() { printf '     %s\n' "$*"; }
fail() { printf '\n\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

for tool in curl jq docker; do
  command -v "$tool" >/dev/null 2>&1 || fail "this script needs '$tool' on PATH"
done
docker compose version >/dev/null 2>&1 || fail "this script needs Docker Compose v2 ('docker compose')"

psql() { docker compose exec -T db psql -U nkap -d nkap -tAc "$1"; }

# --- wait for the stack ---------------------------------------------------------------

say "Waiting for the stack"
for _ in $(seq 1 60); do
  if curl -fsS -o /dev/null "$GATEWAY/actuator/health" \
     && curl -fsS -o /dev/null "$SIMULATOR/_nkap/scenarios"; then
    ok "gateway and simulator are up"
    break
  fi
  sleep 1
done
curl -fsS -o /dev/null "$GATEWAY/actuator/health" || fail "gateway did not come up — try 'docker compose logs gateway'"
curl -fsS -o /dev/null "$SIMULATOR/_nkap/scenarios" || fail "simulator did not come up — try 'docker compose logs simulator'"

# --- 1. script the operator ---------------------------------------------------------

say "1. Scripting the operator: it accepts the submission, then never answers"
curl -fsS -X POST "$SIMULATOR/_nkap/scenarios" \
  -H 'Content-Type: application/json' \
  -d '{
        "rules": [{
          "scenario": {
            "name": "network-drops-after-submit",
            "onSubmit": { "outcome": "NO_RESPONSE" },
            "onQuery": [
              { "status": "PENDING", "reason": "SERVICE_UNAVAILABLE" },
              { "status": "SUCCESSFUL" }
            ]
          }
        }]
      }'
ok "the submission will hang; the first re-query answers SERVICE_UNAVAILABLE, the next SUCCESSFUL"
info "no callback — only the reconciler can resolve this"

# --- 2. submit a payment ----------------------------------------------------------

say "2. POST /payments — the network drops before the operator replies"
response="$(curl -sS -w '\n%{http_code}' -X POST "$GATEWAY/payments" \
  -H 'Content-Type: application/json' \
  -H "Idempotency-Key: demo-$(date +%s)-$RANDOM" \
  -d '{
        "merchantId": "acme",
        "operation": "COLLECT",
        "amount": 5000,
        "currency": "EUR",
        "counterpartyMsisdn": "46733123453",
        "payerMessage": "demo",
        "payeeNote": "demo"
      }')"
status="$(printf '%s' "$response" | tail -n1)"
body="$(printf '%s' "$response" | sed '$d')"

[ "$status" = "202" ] || fail "expected HTTP 202, got $status — body: $body"
ok "HTTP 202 Accepted"

reference="$(printf '%s' "$body" | jq -r '.reference')"
state="$(printf '%s' "$body" | jq -r '.state')"
[ "$state" = "UNKNOWN" ] || fail "expected state UNKNOWN in the response, got '$state'"
ok "state is UNKNOWN — the outcome is genuinely not known, and the gateway does not guess"
info "reference: $reference"

# --- 3. show the payment and the ledger ------------------------------------------

say "3. GET /payments/$reference — and look at the ledger"
state="$(curl -fsS "$GATEWAY/payments/$reference" | jq -r '.state')"
[ "$state" = "UNKNOWN" ] || fail "stored state should be UNKNOWN, is '$state'"
ok "the gateway holds the payment as UNKNOWN"

entries="$(psql "SELECT count(*) FROM ledger_entry WHERE reference = '$reference'")"
[ "$entries" = "0" ] || fail "the ledger should have no entry for this payment yet, has $entries"
ok "the ledger has not moved — no entry, no posting"

# --- 4. wait for resolution -----------------------------------------------------

say "4. Waiting for the reconciler to re-query the operator and settle it"
last=""
resolved=""
for _ in $(seq 1 40); do
  payment="$(curl -fsS "$GATEWAY/payments/$reference")"
  state="$(printf '%s' "$payment" | jq -r '.state')"
  if [ "$state" != "$last" ]; then
    info "state: $state"
    last="$state"
  fi
  case "$state" in
    SUCCEEDED) resolved="yes"; break ;;
    FAILED)    fail "the payment went to FAILED — a timeout must never become a failure" ;;
  esac
  sleep 2
done
[ -n "$resolved" ] || fail "the payment was still $last after 80s"
ok "resolved to SUCCEEDED"

cause="$(printf '%s' "$payment" | jq -r '.history[] | select(.to == "SUCCEEDED") | .cause')"
[ "$cause" = "RECONCILER" ] || fail "the transition into SUCCEEDED is attributed to '$cause', expected RECONCILER"
ok "the transition into SUCCEEDED carries cause RECONCILER — the reconciler resolved it, no callback involved"

# --- 5. show the settled ledger entry ------------------------------------------

say "5. The ledger entry that settlement wrote"
entries="$(psql "SELECT count(*) FROM ledger_entry WHERE reference = '$reference'")"
[ "$entries" = "1" ] || fail "expected exactly one ledger entry, found $entries"

postings="$(psql "SELECT count(*) FROM posting WHERE entry_id = 'collection:$reference'")"
[ "$postings" = "2" ] || fail "expected exactly two postings, found $postings"

# This one can no longer fail: PostgreSQL refuses an unbalanced entry at commit (a deferred
# constraint trigger). It stays here to show the reader what balance means, not because the
# script could catch it being broken — the entry and posting counts above are the checks
# that can actually fire.
sum="$(psql "SELECT coalesce(sum(amount_minor), 0) FROM posting WHERE entry_id = 'collection:$reference'")"
[ "$sum" = "0" ] || fail "the postings sum to $sum — which the database should have refused"

printf '\n'
psql "SELECT '     ' || rpad(account, 32, ' ') || lpad(to_char(amount_minor, 'S999G999G990'), 12, ' ')
      FROM posting WHERE entry_id = 'collection:$reference' ORDER BY seq"
printf '     %-32s %12s\n' '' '-----------'
printf '     %-32s %12d\n' '' 0

say "The network dropped at the worst possible moment, and the accounting truth was not lost."
info "one entry, two postings, summing to zero — enforced by PostgreSQL, not by this script."
printf '\n'
