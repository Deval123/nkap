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

## Adding an operator

1. Implement `ProviderAdapter` in a new `provider-<name>` module.
2. Make the conformance kit pass. It is not optional and it is not adjustable: it
   verifies reference replay, duplicate callbacks, out-of-order callbacks, callbacks for
   unknown references, timeout-then-success, and status flapping.
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

## Versioning and releases

Semantic versioning, and a tag is a promise rather than a bookmark.

- Tags are annotated and named `vX.Y.Z` (`git tag -a v0.1.0 -m "..."`). A lightweight
  tag carries no author, no date and no message, which is exactly the information you
  want a year later.
- **A tag means a GitHub release.** If there is nothing worth writing release notes
  about, there is nothing worth tagging. Untagged `main` is the normal state of this
  project.
- The `pom.xml` version drops `-SNAPSHOT` in the release commit, the tag points at that
  commit, and the next commit opens the following `-SNAPSHOT`.
- Before 1.0.0, the minor number carries breaking changes. `provider-api` is the surface
  that matters here: breaking it breaks every adapter, so it changes in a minor release
  and never in a patch.
- Releases are cut from `main` only, and only when the conformance kit passes.

`v0.1.0` will be tagged when the simulator and the MTN adapter carry one payment end to
end.

## Reporting a security issue

Do not open a public issue. Write to security@nkap.dev with what you found and how to
reproduce it. You will get an acknowledgement within 72 hours.

## Code of conduct

Be decent. Disagree about the code, not the person. Maintainers will remove anyone who
cannot manage that, without a long discussion about it.
