#!/usr/bin/env bash
# Proves on a real cluster that a rotated Kubernetes Secret changes what the operator receives:
# the gateway installed from charts/nkap, its M-Pesa credentials mounted as files (the chart's
# default), a patched Secret, and the next submission's Password computed from the new passkey
# and its token fetched with the new Consumer Key. Issue #240; ADR 0015's amendment.
#
# Run by .github/workflows/credential-rotation.yml, and runnable by hand from the repository root
# with Docker, kind, kubectl, helm and jq on the PATH:
#
#   .github/scripts/kind-credential-rotation.sh
#
# It creates a kind cluster named $CLUSTER (default nkap-rotation) and deletes it at the end;
# KEEP=1 keeps it. It builds the gateway and the M-Pesa simulator from the checkout and loads them
# into the cluster: this tests the branch it runs on, never a published image.
#
# Two disciplines, both on purpose:
#
#   No credential in the log. GET /_nkap/received serves the Consumer Key and Secret in the clear
#   and a Password the passkey can be read back from. They are extracted into variables, compared,
#   and never echoed; a failure names the field that disagreed, not the values. There is no
#   `set -x` anywhere here, and none may be added. The test credentials are generated per run.
#
#   Wait on conditions, never on sleeps. Every wait has a timeout and fails naming what it waited
#   for. The one `sleep` below is a poll interval inside such a wait.
#
# Numbers it prints -- above all how long the rotation took to reach the pod -- belong to the
# cluster that ran it, not to Kubernetes.

set -euo pipefail

CLUSTER="${CLUSTER:-nkap-rotation}"
RELEASE=rotation
GATEWAY="rotation-nkap"
HERE=.github/kind/credential-rotation
# How long a patched Secret may take to reach the pod. Every delay observed so far was 55 to 87s;
# five minutes covers all of them, with margin for a slower cluster, and still fails a hung
# rotation loudly. Why the delay varies is not known: an earlier measurement on the same Kubernetes
# version found about 2s, and nothing explains the difference (ADR 0015's amendment).
ROTATION_TIMEOUT="${ROTATION_TIMEOUT:-300}"
GATEWAY_PORT=18080
MANAGEMENT_PORT=19464
SIMULATOR_PORT=18082
SHORTCODE=174379 # values.yaml's businessShortCode
PROVIDER=mpesa-ke

OWNED=0
FORWARDS=()

say() { printf '\n--- %s\n' "$*"; }
fail() { printf '\nFAILED: %s\n' "$*" >&2; exit 1; }

dump() {
  say "diagnostics"
  kubectl get pods -o wide || true
  kubectl get events --sort-by=.lastTimestamp | tail -n 40 || true
  echo "--- gateway logs"
  kubectl logs "deployment/$GATEWAY" --tail=200 || true
  echo "--- key-init logs, with the printed API key masked"
  kubectl logs "job/$GATEWAY-key-init" --tail=50 2>/dev/null | sed -E 's/nkap_[A-Za-z0-9_-]{43}/nkap_<masked>/g' || true
  echo "--- simulator logs"
  kubectl logs deployment/simulator-mpesa --tail=100 || true
}

cleanup() {
  status=$?
  for pid in "${FORWARDS[@]:-}"; do [ -n "$pid" ] && kill "$pid" 2>/dev/null || true; done
  [ "$status" -ne 0 ] && dump
  if [ "$OWNED" = 1 ] && [ "${KEEP:-0}" != 1 ]; then
    kind delete cluster --name "$CLUSTER" >/dev/null 2>&1 || true
  fi
  exit "$status"
}
trap cleanup EXIT

# Poll `$@` until it succeeds, for at most $1 seconds, naming $2 on timeout.
wait_for() {
  local timeout="$1" what="$2"; shift 2
  local start; start=$(date +%s)
  until "$@" >/dev/null 2>&1; do
    [ $(( $(date +%s) - start )) -ge "$timeout" ] && fail "timed out after ${timeout}s waiting for $what"
    sleep 1
  done
}

b64() { jq -rn --arg s "$1" '$s | @base64'; }

# --- 1. cluster and images ----------------------------------------------------------------------

say "cluster $CLUSTER"
if ! kind get clusters 2>/dev/null | grep -qx "$CLUSTER"; then
  kind create cluster --name "$CLUSTER" --wait 120s >/dev/null
  OWNED=1
fi
kubectl config use-context "kind-$CLUSTER" >/dev/null
kubectl version -o json | jq -r '"server: " + .serverVersion.gitVersion'

say "building the gateway and the M-Pesa simulator from this checkout"
docker build -q -f server/Dockerfile -t nkap-gateway:kind . >/dev/null
docker build -q -f simulator-mpesa-app/Dockerfile -t nkap-simulator-mpesa:kind . >/dev/null
kind load docker-image --name "$CLUSTER" nkap-gateway:kind nkap-simulator-mpesa:kind >/dev/null

# --- 2. PostgreSQL, the simulator, the Secrets, the chart ----------------------------------------

PASSKEY_1="passkey-one-$(openssl rand -hex 16)"
KEY_1="consumer-key-one-$(openssl rand -hex 8)"
SECRET_1="consumer-secret-one-$(openssl rand -hex 16)"
PASSKEY_2="passkey-two-$(openssl rand -hex 16)"
KEY_2="consumer-key-two-$(openssl rand -hex 8)"
SECRET_2="consumer-secret-two-$(openssl rand -hex 16)"

say "secrets, as charts/nkap/README.md documents them"
kubectl delete secret nkap-db nkap-mpesa-ke --ignore-not-found >/dev/null
kubectl create secret generic nkap-db --from-literal=password="$(openssl rand -hex 32)" >/dev/null
kubectl create secret generic nkap-mpesa-ke \
  --from-literal=consumer-key="$KEY_1" \
  --from-literal=consumer-secret="$SECRET_1" \
  --from-literal=passkey="$PASSKEY_1" >/dev/null

say "PostgreSQL and the M-Pesa simulator"
kubectl apply -f "$HERE/postgres.yaml" -f "$HERE/simulator-mpesa.yaml" >/dev/null
kubectl rollout status deployment/postgresql --timeout=180s
kubectl rollout status deployment/simulator-mpesa --timeout=180s

# key-init is a post-install hook that does not wait for PostgreSQL (the chart README says so,
# and says never to retry past it), which is why PostgreSQL is ready before the install starts.
say "helm install from charts/nkap"
helm install "$RELEASE" ./charts/nkap -f "$HERE/values.yaml" --wait --timeout 5m >/dev/null
kubectl wait --for=condition=complete "job/$GATEWAY-key-init" --timeout=180s
kubectl rollout status "deployment/$GATEWAY" --timeout=180s

API_KEY="$(kubectl logs "job/$GATEWAY-key-init" | grep -oE 'nkap_[A-Za-z0-9_-]{43}' | head -n1)"
[ -n "$API_KEY" ] || fail "no API key in the key-init Job's log, where charts/nkap/README.md says it is"

forward() {
  kubectl port-forward "$1" "$2" >/dev/null 2>&1 &
  FORWARDS+=("$!")
}
GATEWAY_POD="$(kubectl get pods -l "app.kubernetes.io/instance=$RELEASE" -o name | grep -v key-init | head -n1)"
forward "service/$GATEWAY" "$GATEWAY_PORT:8080"
forward "$GATEWAY_POD" "$MANAGEMENT_PORT:9464" # the management port is on no Service, by design
forward service/simulator-mpesa "$SIMULATOR_PORT:8082"
wait_for 60 "the gateway through its port-forward" curl -sf "http://localhost:$MANAGEMENT_PORT/actuator/health"
wait_for 60 "the simulator through its port-forward" curl -sf "http://localhost:$SIMULATOR_PORT/_nkap/scenarios"

# --- 3. the measurement --------------------------------------------------------------------------

submit() {
  local code
  code="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "http://localhost:$GATEWAY_PORT/payments" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer $API_KEY" \
    -H "Idempotency-Key: rotation-$(date +%s)-$RANDOM" \
    -d '{"operation":"COLLECT","amount":10000,"currency":"KES","country":"ke",
         "counterpartyMsisdn":"254708374149","payerMessage":"rotation","payeeNote":"rotation"}')"
  # 201 when the operator accepted the submission, 202 when its answer is not yet known; either
  # way the submission reached the simulator, which is all this job reads.
  case "$code" in 201|202) ;; *) fail "POST /payments answered $code, not 201 or 202" ;; esac
}

# The staleness gauge, read and never assumed: present, and zero. A rotation that worked leaves
# it at zero; a rejected candidate would make it count up.
assert_not_stale() {
  local metrics value
  metrics="$(curl -sf "http://localhost:$MANAGEMENT_PORT/actuator/prometheus")" \
    || fail "could not read the management port's /actuator/prometheus"
  value="$(grep -E "^nkap_credentials_stale_seconds\{[^}]*provider=\"$PROVIDER\"" <<< "$metrics" \
    | awk '{print $2}' | head -n1 || true)"
  [ -n "$value" ] || fail "nkap_credentials_stale_seconds{provider=\"$PROVIDER\"} is not exported"
  awk -v v="$value" 'BEGIN { exit !(v == 0) }' \
    || fail "nkap_credentials_stale_seconds{provider=\"$PROVIDER\"} is $value, not 0 ($1)"
}

received() { curl -sf "http://localhost:$SIMULATOR_PORT/_nkap/received"; }

# True when the last recorded submission's Password is base64(shortcode + $1 + its own
# Timestamp), and its shortcode is the configured one. Nothing is printed.
password_matches() {
  local record shortcode timestamp password
  record="$(received)" || return 1
  shortcode="$(jq -r '.lastSubmission.businessShortCode // empty' <<< "$record")"
  timestamp="$(jq -r '.lastSubmission.timestamp // empty' <<< "$record")"
  password="$(jq -r '.lastSubmission.password // empty' <<< "$record")"
  [ "$shortcode" = "$SHORTCODE" ] && [ -n "$timestamp" ] \
    && [ "$password" = "$(b64 "$shortcode$1$timestamp")" ]
}

last_consumer_key_is() {
  [ "$(received | jq -r '.lastTokenRequest.consumerKey // empty')" = "$1" ]
}

say "baseline: a payment with the first credentials"
assert_not_stale "before the first payment"
submit
password_matches "$PASSKEY_1" \
  || fail "baseline: the recorded Password is not base64(BusinessShortCode + first passkey + Timestamp); nothing after this would mean anything"
last_consumer_key_is "$KEY_1" || fail "baseline: the recorded Consumer Key is not the first one"
assert_not_stale "after the first payment"
echo "the first passkey and Consumer Key reached the simulator"

say "rotation: a new passkey, Consumer Key and Consumer Secret, patched together"
kubectl patch secret nkap-mpesa-ke --type merge -p "$(jq -cn \
  --arg k "$KEY_2" --arg s "$SECRET_2" --arg p "$PASSKEY_2" \
  '{stringData: {"consumer-key": $k, "consumer-secret": $s, passkey: $p}}')" >/dev/null
patched=$(date +%s)

# A Password changes only when a submission is made, so the wait submits and looks again, until a
# submission's Password is computed from the new passkey. No fixed delay is assumed: the kubelet
# decides when the mounted files change.
submissions=0
until password_matches "$PASSKEY_2"; do
  [ $(( $(date +%s) - patched )) -ge "$ROTATION_TIMEOUT" ] \
    && fail "${ROTATION_TIMEOUT}s after the patch and ${submissions} submission(s), the gateway still sends a Password that is not computed from the new passkey"
  assert_not_stale "while waiting for the rotation"
  submit
  submissions=$((submissions + 1))
  password_matches "$PASSKEY_2" && break
  sleep 2
done
echo "the new passkey reached the simulator $(( $(date +%s) - patched ))s after the patch, on submission ${submissions} (this cluster, this run)"

# ADR 0015's window: a token refresh already in flight with the old pair can serve one more call
# after the credentials change. So the Consumer Key is asserted on the token request recorded
# after one further submission, never on the first call after the patch. This one line is what
# keeps that window from making the job flaky.
submit
password_matches "$PASSKEY_2" \
  || fail "after the rotation: the recorded Password is not base64(BusinessShortCode + new passkey + Timestamp)"
last_consumer_key_is "$KEY_2" \
  || fail "after the rotation and a further submission: the last recorded token request did not carry the new Consumer Key"
[ "$(received | jq -r '.lastTokenRequest.consumerSecret // empty')" = "$SECRET_2" ] \
  || fail "after the rotation and a further submission: the last recorded token request did not carry the new Consumer Secret"
assert_not_stale "after the rotation"

restarts="$(kubectl get "$GATEWAY_POD" -o jsonpath='{.status.containerStatuses[0].restartCount}')"
[ "$restarts" = 0 ] || fail "the gateway container restarted $restarts time(s): the rotation was not proved without a restart"

say "PASSED: a patched Secret changed the Password and the Consumer Key the operator received, with no restart, and the staleness gauge stayed at 0"
