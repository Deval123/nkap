# ADR 0015 — M-Pesa credentials are read when used, and a bad rotation keeps the last good one

- **Status:** accepted
- **Date:** 2026-09-25

## Context

M-Pesa's passkey is recoverable from any single request (`docs/security-notes.md` §1). No
endpoint for revoking or reissuing one has been observed, so if it leaks, the only lever a
deployer has is replacing it, quickly. Issue #223 argued that replacing it should not need a
restart.

Slice A (#232) let a credential come from a file named after the variable it replaces. That
changed **where** the value is read from, not **when**. `MpesaConfiguration` built one
`MpesaProfile` at startup and `MpesaAdapter` held it in a final field for the life of the
process, so replacing a mounted file still took a restart. The decision on #223 (recorded on the
issue before any code) is what this ADR implements: read the three M-Pesa credentials at the
moment they are used.

Reading at use raises a question startup never had to answer. At startup, a credential that
fails `MpesaProfile`'s validation (a blank value, a byte-order mark, a no-break space pasted from
a portal: #234) stops the gateway, and there is nothing else to do: no value was ever good. At
use, a good value exists. It is in memory, and it was serving payments a moment ago. What the
gateway does with an invalid candidate at that point is a decision, and this ADR is where it is
made.

## Decision

1. **The passkey, the Consumer Key and the Consumer Secret, when supplied as files, are read
   again when their file changes.** The adapter asks for the current credentials each time it
   uses one: the passkey when it computes a request's `Password`, the Consumer Key and Secret
   when it fetches a bearer token. A credential supplied as a variable is not re-read. A process
   cannot see its own environment change.

2. **An invalid or unreadable candidate keeps the last valid credentials, and the payment is
   served with them.** The candidate is validated by `MpesaProfile`'s own constructor, the check
   startup runs, so #234 does not quietly reopen at use. If that throws, or a file cannot be
   read, the candidate is rejected; the installation is not.

3. **That stale use is visible, not only logged.** The first rejection logs one warning naming
   the installation, the file and, for #234's case, which end. It never names the value, its
   length or the character found. It is not repeated while the state lasts. A gauge,
   `nkap_credentials_stale_seconds`, tagged by provider id, reports how long the files have been
   rejected and is zero otherwise. `docs/prometheus-alerts.yml` alerts on it. Recovery logs
   nothing; the gauge returning to zero is the signal.

4. **Startup is unchanged.** A bad value at startup still prevents the context from starting,
   with `MpesaConfiguration`'s message naming the property. The asymmetry between startup and
   use is deliberate: at startup there is no known-good value to fall back on.

5. **Only the credentials are re-read.** The base URL, the business shortcode and the currency
   are fixed at startup, in the server's supplier and again in the adapter, which takes them from
   the first profile it is given and ignores later ones'.

6. **The re-read is gated on each file's modification time.** No file changed since the last
   look, no file is read. Files that changed together are accepted or rejected together.

7. **A changed Consumer Key or Secret drops the bearer token obtained with the old pair.** A
   passkey needs nothing: it is hashed into each `Password` as the request is built.

8. **The change is additive.** `MpesaAdapter` gains a public constructor taking a
   `Supplier<MpesaProfile>`. Its two existing public constructors keep their shape and behaviour,
   a profile fixed for the process's life, and are implemented through the new one with a
   supplier that always returns that profile.

## Rationale

**An invalid value is not a dangerous value.** A passkey that begins with a byte-order mark
authenticates nothing and signs nothing. Serving the payment with the last valid passkey instead
gives nobody anything they did not already have. Refusing the payment would turn a deployer's
typo into an outage, and an outage is not a security gain. The choice at use time is not
fail-closed against fail-open. It is "serve with a credential known good" against "serve
nothing".

**A design where a botched rotation stops payments makes deployers rotate less.** Rotation is
the only defence against a leaked passkey. A deployer who has once taken an outage from a
trailing newline learns to put rotation off. This slice exists to make rotation cheap, and
refusing at use would make it expensive again.

**The error cannot stay hidden.** The last valid value lives only in memory. A deployment that
botches a rotation and later restarts refuses to start, with #234's message naming the field and
which end. The gauge makes it visible before that. The log line is only its trace.

**Why only the credentials.** `MpesaConfiguration.mpesaRoutings` builds the installation's
`ProviderRouting` from its base URL and currency at startup. `ConfiguredAdapterRegistry` takes
each installation's settlement currency from that routing, and `PaymentController` answers
`400 unserved-currency` from it. The `ProviderId` is derived from the country, also at
startup. If a re-read could change the base URL or the currency, the adapter would talk to one
place while routing still described another. The gateway would accept a payment its own routing
had refused, or the reverse, and nothing would report it. So the server's supplier builds each
new profile from the startup fields and fresh credentials. The adapter does not rely on that: it
takes the base URL, shortcode and currency from the first profile it is handed and never asks
again. The invariant is enforced in both places, and the second does not depend on the first.

**Why the adapter drops the token itself.** `MpesaTokenCache.invalidate()` is package-private
inside `provider-mpesa`, and the server has no way to reach it, rightly. The token cache knows
which Consumer Key and Secret obtained the token it holds. When the current pair differs, it
drops the token. The comparison is `String.equals` and produces no message, so neither value can
reach a log or an exception.

**ADR 0008, and why it still holds.** ADR 0008 says an adapter is handed everything it needs to
translate, and never looks anything up. Handing it a supplier instead of a profile changes what
"handed" means: the profile stops being a constant. The rule is still satisfied, because what it
protects is the direction of knowledge. The adapter still reaches for nothing. It does not know
files exist, where they are mounted, that Spring is involved, or where any value comes from. It
calls a `Supplier<MpesaProfile>` it was given at construction. That is exactly what it did with
the profile, one call later. What would break ADR 0008 is an adapter that read a file, an
environment variable or gateway state. None of that is in `provider-mpesa`.

**Why the shape is additive.** The version is `2.1.0-SNAPSHOT`, a minor release, and the two
`MpesaAdapter` constructors are published API of `provider-mpesa`. Neither may change shape or
disappear. Adding a third, and implementing the two through it, keeps one code path rather than
two. The awkwardness of three constructors is compatibility, not indecision.

## Consequences

What this buys is stated above. What it **costs**:

1. **A credential now lives in the heap for the process's lifetime.** It did before, but built
   once. With a last known-good value, the credential in use is the most recent valid one, held
   for as long as the process runs, and a heap dump shows it. This is a real weakening, and a
   small one. The modification-time gate bounds how many copies accumulate. It does not remove
   the one that is held.
2. **A stale value can serve a payment.** "The credentials are the file's" becomes "the
   credentials are the last readable file's". The warning and the gauge are what make that
   acceptable, which is why they are part of this change and not a follow-up.
3. **Validation at use has a failure path startup does not have.** At startup an invalid
   credential prevents starting. At use it arrives during a payment, and the answer, keep the
   last good one and signal, is the decision recorded here, not a default someone will discover.

And what it **does not detect**. A credential that is valid text but wrong, such as another
environment's passkey or a Consumer Key from a different app, passes the startup check and the
use-time check alike. Only Safaricom refuses it, on the next request. Nothing local can tell a
wrong passkey from a right one, and this ADR does not pretend otherwise.

Also:

- **The Helm chart does not gain this yet.** It passes every credential as a variable from a
  Secret reference, and a variable is not re-read. A chart deployment gets rotation without a
  restart only once the chart mounts those Secrets as files in the imported directory, which is
  a separate change.
- **Measured, not assumed:** that a re-read value is trimmed exactly as the startup value was.
  The re-read uses Spring's own config-tree reader with the option Spring's import uses, and a
  test writes a file ending in one newline. The same goes for the gate: a test changes a file's
  content, puts its modification time back, and asserts that nothing is read.
- **Assumed, not measured here:** that a Kubernetes Secret update is seen. Kubernetes swaps a
  symbolic link to a new directory, and the modification time is read through the link, so the
  new file's time is what is compared. No cluster was run for this ADR.
- **A limit of the gate:** a file rewritten within the file system's timestamp resolution,
  keeping the same modification time, is not seen until its next change.
- **The gauge counts from detection**, not from the file's own change. A scrape looks at the
  files through the same gate, so detection is no later than the next scrape or payment,
  whichever comes first.
- A credential supplied by a relaxed-name variable, such as
  `NKAP_PROVIDER_MPESA_INSTALLATIONS_0_PASSKEY`, is not re-read. The supplier asks Spring which
  source the binder used and only follows `application.yml`'s placeholder to a file. In practice
  such a variable replaces the whole installation list, which is its own reason not to use one.

## Alternatives rejected

**Refuse the payment when the candidate is invalid.** This is fail-closed in name only. It
defends nothing, because the rejected value authenticates nothing, and it converts a formatting
mistake into an outage. It is also the design most likely to make deployers stop rotating.

**Use the invalid value anyway, and let Safaricom refuse it.** This discards the one check that
catches #234's cases before they reach an operator. Every payment would then fail upstream with a
message that names nothing.

**Re-read on every use, without a gate.** Simpler, but it multiplies the copies of each
credential in the heap by the payment rate, which is cost 1 made much worse.

**Watch the directory for changes** (`WatchService`). It adds a thread and platform-specific
behaviour, and it misses events on some mounts. It still needs a comparison when the next use
comes, and that comparison is the gate.

**Do MTN in the same change.** MTN's API key has an observable revocation path, and M-Pesa's
passkey does not. That difference is the whole argument for #223. Nothing in this design is
M-Pesa-specific apart from the three credential names. `MtnAdapter` could take a supplier the
same way, and `CredentialFileReader` already resolves any bound property. That is recorded here
as a possibility and not implemented.
