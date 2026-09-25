# nkap — Helm chart

Deploys the Nkap gateway to Kubernetes, from the published image
([`ghcr.io/deval123/nkap-gateway`](https://github.com/deval123/nkap/pkgs/container/nkap-gateway)) —
the same "you clone to contribute, you pull an image to use" argument that produced
`nkap-standalone.compose.yaml`, which this chart otherwise mirrors closely: same environment
variable names, same defaults, same three rules about not inventing a credential. Read that
file's own header first if you have not; this page only covers what a cluster changes. One
exception: this chart can configure M-Pesa (see below), and that compose file cannot yet.

**This chart deploys the gateway only. It does not deploy PostgreSQL.**
`nkap-standalone.compose.yaml`'s own `db` service is a convenience for a single-node
`docker compose up`, never something a production compose stack is meant to keep — a cluster
is exactly the environment where an operator already has an opinion about where PostgreSQL
runs (a managed database, an operator-managed `StatefulSet`, a separate chart), and baking
one choice into this chart would be wrong for most of them. Point `database.host` at
whichever one you run.

## The credential rule — not negotiable

**This chart does not accept a credential value anywhere in `values.yaml`.** Not the database
password, not an MTN subscription key, api user or api key, not an M-Pesa consumer key,
consumer secret or passkey, not a webhook signing secret.
Every credential is a *reference* — the name of a Kubernetes `Secret` you create yourself, and
the key inside it — never a value. Rendering **fails**, naming exactly what is missing, if a
required reference is absent:

```
$ helm template . 
Error: execution error at (nkap/templates/keyinit-job.yaml:44:61):
image.tag is required -- set it to a real release, e.g. "1.0.0" (see https://github.com/deval123/nkap/releases)
```

**Why this is not a preference.** `nkap-standalone.compose.yaml` established the rule for
compose: nothing invents a credential, every required value fails fast and names itself. A
Kubernetes `values.yaml` makes the same mistake easier to commit and more permanent than a
`.env` file ever was — it is the file an operator's own GitOps repository holds, checked in
on purpose, meant to be reviewed and diffed. A field that accepted `mtn.installations[0].apiKey:
<value>` would be an invitation to put a real MTN API key in git, and the value of saying
that plainly out loud is the entire reason `docs/security-notes.md` exists. There is
deliberately no "quick" inline path, even a dev-only one clearly named as such: this chart is
not the "just try it" path — `compose.yaml` and `examples/demo.sh` already are, and stay that
way.

Create each Secret before installing, e.g.:

```bash
kubectl create secret generic nkap-db --from-literal=password="$(openssl rand -hex 32)"
kubectl create secret generic nkap-mtn-cm \
  --from-literal=subscription-key=... \
  --from-literal=api-user=... \
  --from-literal=api-key=...
```

then name them in your own values file:

```yaml
database:
  host: postgresql.default.svc.cluster.local
  existingSecret: nkap-db

provider:
  default: mtn-cm
  mtn:
    installations:
      - country: cm
        currency: XAF
        baseUrl: "https://momodeveloper.mtn.com"
        targetEnvironment: mtncameroon
        existingSecret: nkap-mtn-cm
```

See `values.yaml`'s own comments for every field, including disbursements (a separate MTN
product with its own Secret) and more than one country installation (issue #82).

**`provider.default` must name a configured installation.** Left unset, the gateway falls back
to `mtn-cm`, which exists only if an MTN Cameroon installation is configured. A release that
configures installations but whose default names none of them fails to start, so a release
serving only M-Pesa Kenya sets `provider.default: mpesa-ke`. A release with no installation at
all still starts. As with undeclared countries, this chart leaves the check to the gateway.

**Only the countries the gateway has a slot for exist: MTN `cm` and `gh`.** An MTN installation
for any other country renders without complaint, and then the gateway refuses to start, naming
the country: its variables would be read by nothing, and its credentials would sit in the pod
unused. This chart deliberately does not check it itself. A chart-side list would be a second
copy of the gateway's slots, and it would protect only Helm installs; the gateway's own check
covers every way of deploying it.

### M-Pesa

M-Pesa (Safaricom's STK Push, collections only) is configured under
`provider.mpesa.installations`, with its credentials in a Secret of their own:

```bash
kubectl create secret generic nkap-mpesa-ke \
  --from-literal=consumer-key=... \
  --from-literal=consumer-secret=... \
  --from-literal=passkey=...
```

```yaml
publicBaseUrl: "https://nkap.example.com"

provider:
  mpesa:
    installations:
      - country: ke
        currency: KES
        baseUrl: "https://sandbox.safaricom.co.ke"
        businessShortCode: "174379"
        existingSecret: nkap-mpesa-ke
```

Three things differ from MTN, and rendering enforces each one:

- **One installation, Kenya.** The gateway has exactly one M-Pesa slot. A second entry, or a
  country other than `ke`, fails rendering rather than producing variables the gateway would
  never read. `values.yaml` says why there is one slot.
- **`publicBaseUrl` is required.** The callback is the only way the gateway resolves an M-Pesa
  payment whose submission was lost, and the gateway refuses to start an M-Pesa installation
  without an address to be called back on. Rendering fails first, so you see it at
  `helm install`, not in a crash-looping pod.
- **`businessShortCode` must be quoted.** Unquoted, YAML reads it as a number, and Helm renders
  a seven-digit one as `7.234567e+06`.

The passkey is recoverable from any single request Nkap sends to Safaricom
(`docs/security-notes.md` §1), and the rotation procedure written for Nkap API keys does not
apply to it. Keep it out of every values file, which this chart already forces.

**The three credentials arrive as files by default.** The credential rule is unchanged: they
still come from the Secret you name, never from values. What changes is how the Secret reaches
the gateway. There are two forms, chosen per installation by `credentialsAs`:

- **`files`, the default.** The Secret is mounted read-only at `/etc/nkap/credentials`, as three
  files named `NKAP_PROVIDER_MPESA_KE_CONSUMER_KEY`, `NKAP_PROVIDER_MPESA_KE_CONSUMER_SECRET` and
  `NKAP_PROVIDER_MPESA_KE_PASSKEY`. Those names are fixed by the gateway, which reads a file named
  exactly like the variable it replaces. `consumerKeySecretKey`, `consumerSecretSecretKey` and
  `passkeySecretKey` still choose which key of your Secret feeds each file. The chart sets
  `NKAP_SECRETS_DIR` to the mount path from the same definition, so the two cannot disagree.
  Files are the form the gateway re-reads when they change
  ([ADR 0015](../../docs/adr/0015-credentials-read-at-use.md)), so a replaced passkey can reach it
  without a restart. Measured on one `kind` cluster, Kubernetes `v1.35.0`: a patched Secret
  reached the pod after about 2 seconds, and the gateway detects the change by the file's real
  path, since the update leaves its modification time alone. **The whole path, to Safaricom
  accepting the new passkey, is not yet measured.** Until it is, do not count on it for an
  incident; restarting the pods after replacing the Secret is the certain path.
- **`env`.** The three credentials are environment variables, as this chart rendered them
  before, read once at startup. For a cluster that cannot mount Secrets as volumes, or an
  operator who prefers variables. Replacing the Secret then takes a restart.

The choice is per installation, not per credential, because the gateway refuses to start when
one name arrives both as a variable and as a file. In the `files` form the three variables are
not rendered at all. Any value other than `files` or `env` fails rendering and names the two.

The mount is not the gateway's default directory, `/run/secrets`. In the gateway's image
`/var/run` is a link to `/run`, and `/var/run/secrets` is where Kubernetes mounts the pod's
service-account token, which the gateway would otherwise import too. The key-init Job mounts the
same volume, because it starts the same application context and validates the same
installation.

## Installing

```bash
helm install my-nkap ./charts/nkap \
  --set image.tag=1.0.0 \
  -f my-values.yaml   # database.host, database.existingSecret, keyInit.merchantId, at minimum
```

Then read the three questions below — before you rely on this deployment, not after.

## Three questions a chart forces that a compose file never did

### 1. Where does the first API key appear, and who else can read it?

`keyInit` runs `--nkap.apikey.create` as a `post-install` Helm hook Job — the same command
and the same one-shot shape as `nkap-standalone.compose.yaml`'s own `key-init` service. The
key is generated by the gateway, printed **once**, and only its hash is ever stored; there is
no command or route that shows it again.

**This is a new exposure this chart introduces, not a variation on an old one.** In compose,
`docker compose logs key-init` reads a log that exists only on the machine running it. In a
cluster, `kubectl logs job/<release>-nkap-key-init` reads from whatever your cluster's log
pipeline retains — and if that pipeline ships pod logs anywhere else (a log aggregator, a
SIEM, a hosted logging product), the key is now there too, for as long as that system retains
logs, which is very likely longer than the seconds it took you to read it off `kubectl logs`.
This chart cannot know your log pipeline and does not pretend to.

**What to do about it, in order of preference:**

- **Rotate after reading.** Read the key from the Job's logs, use it once to provision a
  proper key through your merchant's own process, then revoke the printed one as
  compromised, the same one-off-pod shape `NOTES.txt` shows for provisioning:

  ```bash
  kubectl run nkap-apikey-revoke --rm -it --restart=Never \
    --image=<the same image and tag> \
    --env=NKAP_DB_URL=... --env=NKAP_DB_USER=... --env=NKAP_DB_PASSWORD=<from your database Secret> \
    -- --nkap.apikey.revoke --nkap.apikey.id=<the printed key's id>
  ```

  It stops authenticating on its very next use — see `docs/security-notes.md` §1 ("Key
  revocation") for what marking the row actually does.
- **Provision out-of-band.** Set `keyInit.enabled: false` and run the provisioning command
  yourself, once, from a pod whose logs you control (or that you delete immediately after
  reading), the same way `NOTES.txt` shows after a `keyInit.enabled=false` install.

Either way, delete the completed `key-init` Job once you have the key and no longer need
`kubectl logs` to read it back — it is kept by default (`helm.sh/hook-delete-policy:
before-hook-creation` only removes a *previous* run, and a `post-install` hook never runs
again on the same release) specifically so you have the chance to read it, not because
leaving it forever is recommended.

**If the Job fails, do not raise `backoffLimit`.** It is `0` on purpose, not a cautious
default: `keyInit` is handed no `--nkap.apikey.token`, so it is not idempotent the way a
retried HTTP request is — every attempt mints a brand new key. And the failure it would
retry on is the most likely one in this chart's own default topology: this chart deploys no
PostgreSQL and this Job does not wait for one to answer, so a first `helm install` racing a
database still coming up fails right here, as the common case, not an edge case. Setting
`backoffLimit: 3` to paper over that turns one ordinary startup race into up to four live
keys, three of them printed only into the logs of pods that already failed — logs nobody
reads, because the Job looks broken rather than provisioned, and that your cluster's own log
retention may garbage-collect before anyone does. Revoking one is a command now
(`docs/security-notes.md` §1), not a database delete — but it still needs someone to notice
the extra key and its id first, which a failed-looking Job actively works against: an
unnoticed extra key stays valid indefinitely, not until someone happens to revoke it.
**When this Job fails: fix database reachability and reinstall, or set `keyInit.enabled:
false` and provision out-of-band** (see above) — never retry your way past it.

### 2. What runs the migrations when there is more than one replica?

**Nothing separate — Flyway still runs at startup, in every pod, exactly as it does today.**
No `pre-upgrade` Job exists in this chart. Two things make that the right call rather than an
oversight:

- **Concurrent pods migrating at once is already safe.** Flyway takes its own lock
  (a session-level advisory lock on PostgreSQL) before applying migrations, so several pods
  starting together — a fresh `helm install`'s Deployment and its `key-init` Job racing each
  other, or a `replicas: 3` Deployment scaling up — serialise on that lock rather than racing
  the schema. This has always been true; a chart is simply the first artefact where more than
  one pod starting at once is the common case rather than an edge case.
- **A rolling upgrade is a different question, and this is the constraint to know rather than
  the one this chart removes.** Kubernetes' default `RollingUpdate` strategy means the old
  image and the new one run pods against the *same* schema at the same time, for however long
  the rollout takes. Flyway's lock makes concurrent *migration* safe; it says nothing about
  whether the *old* code can tolerate a schema the *new* code's migration already applied.
  `MigrationRollbackIT` proves this project's migrations have a hand-written, tested inverse
  each — it does not prove two versions of the application can coexist against one schema,
  because that is a fact about the application code in each release, not about the migration
  scripts. **The safety of a rolling upgrade rests on every migration staying additive and
  backward-compatible with the previous release** — the discipline this project's own
  migration history (`V1` through `V9`, each adding rather than rewriting) already follows,
  not a guarantee this chart can enforce for you. A migration that ever needs to *not* be
  additive is a migration that needs `maxUnavailable: 100%` (effectively: stop everything,
  then upgrade) for that one release, set by hand in that release's own values, not a chart
  default paid by every release that does not need it.

### 3. Is more than one replica safe at all?

**Yes — and naming the mechanism is the point of asking, so the claim can be checked rather
than believed.** Nkap's concurrency-safety has been horizontal from the start, for reasons
that predate this chart by several slices:

- The reconciler and the outbox relay both claim their work with
  `SELECT ... FOR UPDATE SKIP LOCKED` (`PostgresReconciliationStore`,
  `PostgresOutboxRelayStore`) — each due row is claimed by exactly one pod's transaction, and
  every other pod's identical query simply skips rows already locked and claims what remains.
  Running the reconciler or the relay in more than one pod does not duplicate work; it divides
  it.
- Settlement (`SettlementService`) takes the payment's own row with `SELECT ... FOR UPDATE`
  before applying a confirmed outcome, so two pods confirming the same payment at once
  serialise on that row rather than racing to write two transitions.
- The idempotency store's `begin` is atomic on `INSERT ... ON CONFLICT`, proved under
  concurrency (`IdempotencyStoreIT`, "two concurrent begin calls with the same key: exactly
  one Proceed") — two pods handling a retried `POST /payments` for the same idempotency key
  do not both proceed as if they were first.

None of this is new to this slice. What is new is that a chart *invites* `replicas: 3` in a
way a single compose file never did, and an operator who sets it deserves to read that this
was intended rather than infer it from the absence of a warning.

## The management port

`9464` (`management.server.port`, `application.yml`) carries payments-by-state, reconciler
passes, escalations, the suspense balance in a real currency, and, since issue #113, the list
of escalated payments by reference and merchant — the first row-level rather than aggregate
data this port carries. Every compose file in this repository refuses to publish it, and this
chart holds the same line. `templates/service.yaml` — the only `Service` an `Ingress` should
ever front — exposes `8080` only. Liveness and readiness probes target `9464` directly on the
pod, where it is reachable regardless of what any `Service` exposes, the same way
`compose.yaml`'s own healthcheck does.

A second, separate `Service` for a Prometheus scrape target exists at
`templates/service-metrics.yaml`, gated behind `metrics.service.enabled` (default `false`) and
labelled `app.kubernetes.io/component: metrics` so it reads as what it is. Turning it on is a
deliberate choice about who else can reach your scrape network; keep it off any `Ingress`.

## What this chart does not do

- **Deploy PostgreSQL.** See above.
- **Provision a webhook endpoint.** `nkap-standalone.compose.yaml` does not either — webhook
  secrets are generated and printed the same way API keys are (`docs/webhooks.md`), and
  adding that as a second hook Job with the same log-exposure question as `keyInit` is real
  work for a later slice, not a gap in this one. Provision one from a one-off pod running the
  same image, the same way `NOTES.txt` shows for an API key when `keyInit.enabled` is `false`.
- **An `Ingress`.** Point your own at `templates/service.yaml`'s `Service`, on `8080`.
- **A dev-only inline-credential escape hatch.** See "The credential rule" above.

## What CI checks, and what it does not

`.github/workflows/build.yml`'s `helm` job runs `helm lint` and `helm template` against a
values file naming Secrets by name only (`charts/nkap/ci/values-test.yaml`) and asserts, on
the rendered output:

- the traffic `Service` exposes `8080` and nothing else;
- rendering a values file that supplies no credentials **fails**, naming what is missing,
  rather than producing a Secret-less Deployment that would fail only once a pod actually
  started;
- both probes target the management port;
- every credential-bearing environment variable — the database password, each MTN
  installation's subscription key, api user and api key, and their disbursement
  equivalents — is sourced from `secretKeyRef` in the rendered output, checked line by
  line, never a plain `value:`;
- with `charts/nkap/ci/values-mpesa-test.yaml`: rendering fails, naming the field, for each
  missing M-Pesa field, a missing `publicBaseUrl`, a country other than `ke`, an unquoted
  shortcode and a second installation; the Kenya slot's variables render as expected; the
  consumer key, consumer secret and passkey are mounted as files by default, in both the
  Deployment and the key-init Job, and are then **not** also rendered as variables (the gateway
  would refuse to start); the mount path and `NKAP_SECRETS_DIR` agree; with
  `charts/nkap/ci/values-mpesa-env-test.yaml` (`credentialsAs: env`) the three are
  `secretKeyRef` variables and no credential volume renders; an unknown `credentialsAs` fails,
  naming `files` and `env`; and no variable whose name looks like a credential renders as a
  literal `value:` in any render, whether or not anyone remembered to list it;
- the MTN-only render carries no M-Pesa variable and no public base URL.

**What CI does not do: install this chart into a real cluster.** `helm template` proves the
chart renders correctly; it does not prove the rendered manifests actually schedule, that the
`key-init` hook actually completes before `NOTES.txt`'s instructions make sense, that the
probes actually pass against a real container, or that a rolling upgrade behaves the way the
question above describes. `release.yml`'s `verify-pull` job is the honest version of this
argument for the compose path — pulling the real published images with no credentials and
running the real demo against them — and a `kind`-cluster equivalent for this chart is real,
undone work, not a nicety. Until it exists, treat this chart as unit-tested, not
integration-tested.
