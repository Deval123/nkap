# conformance

The test kit every `ProviderAdapter` must pass before it is merged.

This module is what makes the project governable beyond its author: it lets a maintainer
accept an adapter for an operator they have no account with. If the kit passes, the
adapter is acceptable.

## How it was built

**Extracted, not designed.** Every rule here was lifted from a test that already passes
against MTN — the first real implementation. A rule that no adapter has ever satisfied is
not in the kit. Three ADRs were corrected by implementation in the weeks this was written;
the evidence is fairly clear about which method produces requirements that survive.

## The two pieces

**`ProviderAdapterConformanceTest`** — an abstract JUnit class whose `@Test` methods are the
rules. It lives in `src/main` (with JUnit in compile scope) so a provider module can extend
it from its own test code.

**`ConformanceHarness`** — the interface a contributor implements to plug their adapter in.
It exposes the adapter and the means to put the operator into each condition, *expressed in
terms of what the gateway observes, never in an operator's own codes*. Writing one is the
contributor's real work, and the thing to judge this kit by.

The kit **does not depend on `nkap-simulator`**. The simulator wears MTN's shape; a future
Orange adapter may be driven by something else entirely.

## What the kit checks

Adapter-level rules, each already proven for MTN:

1. The same reference submitted twice produces one payment, not two.
2. A call that does not answer yields `UNKNOWN`, never a failure — and a later query may
   still resolve it to `SUCCEEDED`.
3. An outright refusal yields `SubmitResult.Rejected`, not an exception and not an
   acknowledgement.
4. A status that flaps is reported faithfully and cannot reopen a terminal payment.
5. An expired credential mid-flight is renewed and the call retried **with the same
   reference**.
6. A callback for a reference the gateway never issued is rejected, writing nothing.

## What it does not check, and why

**Waiting on [issue #26](https://github.com/Deval123/nkap/issues/26).** *A code the adapter
does not recognise maps to `UNKNOWN`.* This rule is real and proven — but at unit level, in
`MtnStatusMapTest`, which feeds the map directly. It cannot be driven through a harness
today because a simulator scenario can only declare a known status, never an arbitrary
operator code. It joins the kit when #26 lands. A conformance kit with an optional method
is not a conformance kit, so the method is left out entirely until then.

**Not adapter properties at all.** Two cases from the roadmap belong to a future
gateway-level suite, once `server` exists:

- *A duplicate callback produces one state transition.*
- *A callback arriving before the submit response is not lost.*

Both are properties of the gateway's state machine and its idempotency store, not of an
adapter — which only parses what it is handed.

## Using it

In your `provider-<name>` module, in **test scope**:

1. Add a dependency on `nkap-conformance`.
2. Implement `dev.nkap.conformance.ConformanceHarness` for your operator.
3. Add a test class that `extends dev.nkap.conformance.ProviderAdapterConformanceTest` and
   returns your harness from `newHarness()`.

See `provider-mtn` (`MtnConformanceHarness`, `MtnConformanceTest`) for a worked example.
