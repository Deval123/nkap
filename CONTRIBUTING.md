# Contributing to Nkap

Thank you for considering it. This document is short on ceremony and specific about the
few things that are non-negotiable, because the subject is other people's money.

## Where to start

The most accessible contribution is a **simulator scenario**: one operator failure mode
per pull request. It needs no operator account, no credentials, and no knowledge of
double-entry bookkeeping — only the ability to make a fake server behave badly on
command. Issues labelled `good first issue` are all of this shape.

The hardest contribution to get merged is a change to `core`. That is by design.

## Ground rules

**The ledger is append-only.** No pull request adds an `UPDATE` or a `DELETE` on ledger
entries. Corrections are reversing entries. If you believe you have found a case that
needs mutation, open an issue first — you will either have found a real design flaw,
which is valuable, or a misunderstanding worth writing down.

**A timeout is not a failure.** No code path may conclude that a payment failed without
an explicit statement from the operator. An unreadable, absent, or unrecognised response
maps to `UNKNOWN`. This rule has no exceptions and no "but in this case".

**`core` stays dependency-free.** No web framework, no ORM, no JSON library, no Spring.
If your change to `core` needs a dependency, the change belongs in another module.

**Money is an integer.** Any pull request introducing `double`, `float`, or a currency
conversion outside a position account will be declined with a link to this paragraph.

These four are enforced by tests. [`docs/conventions.md`](docs/conventions.md) states them
alongside the module boundaries, the branch, commit and pull request conventions, and the
build commands — read it before opening a first pull request.

## Adding an operator

1. Implement `ProviderAdapter` in a new `provider-<name>` module.
2. Make the conformance kit pass. In that module's **test scope**, depend on
   `nkap-conformance`, implement `dev.nkap.conformance.ConformanceHarness` to drive your
   operator into each condition, and add a test class that
   `extends dev.nkap.conformance.ProviderAdapterConformanceTest` and returns your harness.
   It is not optional and not adjustable: it checks reference replay, timeout-then-success,
   outright refusal, status flapping, credential renewal, and untrusted callbacks. See
   `provider-mtn` for a worked example. If writing the harness is laborious, say so in your
   pull request — that is a signal about the contract, not about you.

   You do not write the plumbing around it. The kit's `CallbackReceiver` catches your
   operator's callbacks on a route private to each harness. If your operator has a simulator
   in this repository, `nkap-test-support`'s `SimulatorUnderTest` boots it and drives its
   `/_nkap` control plane; depend on it at test scope, never at any other. It stays out of the
   kit because it brings Spring Boot with it, and the kit depends on `provider-api` and JUnit
   alone. The module boundaries are in [`docs/conventions.md`](docs/conventions.md).
3. Document the operator's quirks in `docs/providers/<name>.md` — the undocumented status
   codes, the field that is sometimes absent, the sandbox that lies. This file is often
   more valuable than the code.

The conformance kit exists so that an adapter for an operator nobody on the project has
an account with can still be reviewed and merged. If the tests pass, the adapter is
acceptable.

## Pull requests

- One concern per pull request. A scenario, a bug, a refactor — not all three.
- New behaviour comes with a test. In `core`, the test comes first.
- Test names are sentences that state the rule being defended, not method names:
  `a timeout moves a payment to UNKNOWN, never to FAILED`.
- Comments explain *why*, never *what*. The code already says what.
- Sign your commits off with `git commit -s` (Developer Certificate of Origin).

## Amending an ADR

An ADR records a decision and the reasoning that led to it — not a claim that stays true
forever. When later work bounds, extends, or corrects what one says, without replacing the
decision itself, amend it in place rather than rewriting it. The decision text and its
reasoning are never edited — a reader must still be able to see what was decided then, and
why it made sense at the time.

1. **A dated `## Amendment, <date> — <issue>` section**, at the foot of the ADR, after
   *Alternatives rejected* if it has one. Say what is no longer accurate, what changed it
   and by which issue, and what of the original reasoning still stands.
2. **An inline marker on every row or sentence the amendment affects**, pointing at that
   section. A reader who reaches the affected passage first — which is the normal way an ADR
   is read, a table consulted and the file closed — and never reaches the foot of it must
   still see that it is bounded. The amendment section alone does not reach that reader.

Both steps apply the same way whether what changed is a decision later work narrowed (the
rule was right, its scope was too wide) or a plain factual error (a wrong number, a wrong
name): a reader needs the same thing from either — this passage is no longer accurate, here
is why, here is what still stands. A one-sentence amendment for a plain error is a complete
amendment; it does not need the weight of the bounded-decision case to earn the same shape.

**The amendment points; it does not copy.** If the rule as it stands today already has a
home with a test that keeps it honest against the code — `docs/providers/mtn.md`'s
status-and-error tables, guarded by `MtnStatusMappingDocTest`, are the model — the amendment
names that file and section instead of restating the rule. A rule written in two places
drifts from one of them, and it will: issue #126 removed exactly this kind of duplication
from `docs/providers/mtn.md` itself, after it had drifted twice in two days.

**Reach for a new, superseding ADR only when the original decision is actually replaced.**
If the earlier decision would be actively wrong to follow today, write a new ADR that
supersedes it and say so in both. If the earlier reasoning is still right for the case it
addressed, and later work only narrowed, extended, or corrected it, amend instead — a
superseding ADR overstates what happened.

[ADR 0004](docs/adr/0004-mtn-adapter.md)'s `## Amendment, 2026-09-18` section is the worked
example this convention was written for: a decision issue #115 bounded, and a plain factual
error, amended the same way in the same section, each with its own inline marker.

## Versioning and releases

Semantic versioning, and a tag is a promise rather than a bookmark. **A tag means a GitHub
release.** If there is nothing worth writing release notes about, there is nothing worth
tagging. Untagged `main` is the normal state of this project.

Once that judgment is made, cut the release from `main` only, once the conformance kit
passes, in exactly this order:

1. **Drop `-SNAPSHOT`, by hand.** Every POM in the reactor, and the CHANGELOG entry for the
   version being cut gets its date. This is the release commit.
2. **Tag it, by hand.** Annotated `vX.Y.Z` (why annotated, below), on the release commit,
   once it is on `main`. Push the tag the same day the release commit merges: that commit
   bumps `README.md`'s quickstart `curl` to
   `raw.githubusercontent.com/deval123/nkap/v<version>/...`, and that URL 404s until the tag
   exists — minutes apart if the two are done together, a day if the tag waits.
3. **The workflow runs itself.** Pushing the tag triggers `.github/workflows/release.yml`
   with nobody doing anything: the full reactor build, then the images published, then
   pulled back down with no credentials and run through the demo to prove the publish
   actually works. See below for exactly what it does, including the one manual step
   inside it.
4. **Create the GitHub release, by hand.** Nothing in this repository does this
   automatically. Body: the version's own `CHANGELOG.md` entry, copied verbatim from below
   its heading to the next one; title `Nkap X.Y.Z`; neither draft nor prerelease.
5. **Reopen the next `-SNAPSHOT`, by hand.** Every POM in the reactor. Until this lands,
   `main` sits on a released version, and a build from it produces an artifact claiming a
   version it is not.

- Tags are annotated (`git tag -a v0.1.0 -m "..."`), never lightweight: a lightweight tag
  carries no author, no date and no message, which is exactly the information you want a
  year later.
- Until 1.0.0, the minor number carried breaking changes — `provider-api` is the surface
  that matters, so breaking it took a minor release, never a patch. From 1.0.0 it takes a
  major release, the discipline that makes third-party adapters possible.
  `nkap-conformance` is not held to that rule, though contributors build against it too:
  it is a test-scope dependency, so breaking it fails a contributor's build loudly, before
  anything runs, and never makes an adapter misbehave where money moves. A minor release
  may break it, and its release note must say so.
- Step 3, in full: the workflow runs the full reactor build, then publishes
  `ghcr.io/deval123/nkap-gateway`, `ghcr.io/deval123/nkap-simulator` and
  `ghcr.io/deval123/nkap-simulator-mpesa` for `linux/amd64` and `linux/arm64`, tagged
  `X.Y.Z` and the moving `latest` — never on a push to `main`, and never a rolling `X.Y` or
  `X` tag (until 1.0.0, a minor release could break `provider-api`; a tag that moves across
  one silently is the wrong default for a payments gateway). It then
  pulls what it just published, from a job with no registry credentials, and runs
  `examples/demo.sh` against `nkap-standalone.compose.yaml` — the file a real deployment
  downloads — and runs the M-Pesa simulator on its own, since the demo does not use it. A
  publish nobody can pull, or that only works from a clone, fails the build.
  GHCR packages default to private on their first publish and there is no supported way to
  flip that from the workflow alone (see the workflow's own comment); after the very first
  tagged release, a maintainer sets each package to public once, by hand, in its package
  settings — including `nkap-simulator-mpesa` at the first release that publishes it, though
  the other two are long public — the pull-and-verify step above is what catches this being
  forgotten.

`v1.0.0` was Nkap's first release; its scope was fixed in
[`docs/roadmap/v1.0.0-mtn-end-to-end.md`](docs/roadmap/v1.0.0-mtn-end-to-end.md), which is a
historical record now, not a plan — the file says so itself. `main` has carried a tag, cut
by the sequence above, at every release since.

## License

Contributions are accepted under the Apache License 2.0, the license this whole project is
released under, certified by the Developer Certificate of Origin (DCO) — sign every commit
off (`git commit -s`). See <https://developercertificate.org> for exactly what that
certifies; it is not restated here. **The sign-off is checked on every pull request** — a
trailer that does not match the commit's own author, by name or by email, fails the build.

This project asks for **no copyright assignment and no CLA**. You keep the copyright to your
own contribution. The consequence — spelled out in
[ADR 0012](docs/adr/0012-apache-2-0-no-cla.md) — is that this project's license cannot change
without every contributor's agreement, which in practice means it will not change. That is a
deliberate trade, not an oversight, and you should be able to learn it here before your first
pull request rather than discover it later.

## Reporting a security issue

Do not open a public issue. See [`SECURITY.md`](SECURITY.md) for how to report one and what
happens next.

## Code of conduct

Be decent. Disagree about the code, not the person. Maintainers will remove anyone who
cannot manage that, without a long discussion about it.
