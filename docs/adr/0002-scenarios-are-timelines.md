# ADR 0002 — A simulator scenario is a timeline, selected by rules through a control plane

- **Status:** accepted
- **Date:** 2026-09-08

## Context

The simulator exists to reproduce the ways a mobile money operator misbehaves, so that a
client can be tested against them. Eight behaviours are wanted (issues #3 to #10): latency,
timeout then late success, duplicate callback, callback before the submit response,
callback for an unknown reference, flapping status, token expiry mid-flight, and
declarative scenario files.

The obvious model — a scenario is the response to return — collapses immediately. A
timeout followed by a late success is a submission that never answers *and then* a query
that succeeds. A flapping status is `SUCCESSFUL` on one query *and then* `FAILED` on the
next. Neither is a response; both are sequences.

A second question is how a scenario reaches a given request. The real MTN sandbox encodes
some failures in magic MSISDNs. Configuring one scenario per running instance is a third
possibility.

## Decision

**A scenario is a timeline** — a plain data record describing what happens at each
interaction point:

- `onSubmit`: a delay, and an outcome among `ACCEPT`, `CONFLICT`, `BAD_REQUEST`,
  `SERVER_ERROR`, `NO_RESPONSE`.
- `onQuery`: an ordered list of behaviours, one per successive query. **The last entry
  repeats indefinitely**, so a client that polls more times than the scenario declares
  keeps getting a defined answer instead of falling off the end.
- `callbacks`: each with a delay relative to submission, a number of deliveries and the
  interval between them, and a target reference that is either the request's own or an
  unknown one. They are delivered to the URL in the submit request's `X-Callback-Url`
  header, or failing that one declared with the rules; with neither, nothing is sent.

**Scenarios are selected by ordered rules, declared over HTTP.** A control plane under
`/_nkap/` accepts a list of rules, each pairing a matcher (on reference, MSISDN, amount or
currency) with a scenario. First match wins; no match means the happy path.

**The token lifetime is declared alongside the rules, not inside a scenario.** A bearer
token is obtained before any payment exists, so it cannot belong to a payment's timeline.
It is a property of the simulated operator session, and it is set in the same document as
the rules so that one call replaces the whole declared configuration atomically.

**The scenario is resolved once, at submission, and frozen against the reference.**

**Controllers decide nothing.** They ask the engine what to do and carry it out.

## Rationale

**The timeline shape is validated by the eight scenarios.** Every one of them is
expressible in it without adding a field, which is the test that an abstraction sits at the
right level. A model that needed a special case for the ninth would be the wrong model.

**A flat data record makes issue #10 trivial.** Loading scenarios from YAML becomes a
Jackson binding rather than a project, because the in-memory model and the file format are
the same shape by construction.

**HTTP control, not a Java fixture.** The simulator's strategic value is that it is useful
on its own, to developers who will never run Nkap and who mostly do not write Java. A
control plane driven over HTTP is usable from a Python, PHP or JavaScript test suite. A
Java test fixture would quietly make the simulator a Java-only tool.

**Rules beat magic values.** Magic MSISDNs need no state, but the mapping is frozen in
code: a contributor cannot add a case without recompiling, and a test cannot express "this
particular payment fails". Rules keep the magic-value behaviour available — a rule matching
an MSISDN is exactly that — while letting a test declare its own.

**Freezing at submission** is what makes a failing test diagnosable. If rules were consulted
again at each query, changing them mid-test would silently switch a payment from one
scenario to another, and the resulting behaviour would belong to no scenario anyone
declared.

**`NO_RESPONSE` must not block a thread.** It is implemented with a `DeferredResult` that
never completes. A `Thread.sleep` would exhaust the servlet pool as soon as a handful of
tests run in parallel — and tests running in parallel is exactly what a simulator is for.

## Consequences

- The simulator keeps per-reference state: the resolved scenario and a count of queries so
  far. `DELETE /_nkap/state` resets it, so tests can be isolated from one another.
- The control plane is namespaced under `/_nkap/` so it can never collide with an operator
  path, and it is the one part of the simulator that deliberately does not imitate MTN.
- Callbacks are delivered by a scheduler. They are scheduled when the scenario resolves,
  before the submit delay is applied, so a zero-delay callback can reach the client before
  its submit call returns (issue #6). Delivery is a plain outbound `POST` with no retries:
  the simulator sends what the scenario declares and no more. The accepted risk above did
  materialise — `CallbackSpec` had a repeat count but no interval, without which "the same
  callback twice, a configurable interval apart" (issue #5) could not be expressed. It was
  one field, added when delivery was built, and no file anyone had written needed to
  change. That is the outcome the risk was accepted for.
- Scenarios must stay deterministic. No randomness, no dependence on wall-clock time beyond
  the declared delays: a scenario that behaves differently on two runs is worse than no
  scenario.

## Correction, 2026-09-08

The first version of this ADR put the token lifetime inside `Scenario`, and said the token
endpoint should use "the scenario resolved for the most recent submission". Implementing it
showed the mistake immediately.

A token is requested *before* any payment exists, so there is no reference to resolve it
against. "Most recent submission" was not a design, it was the only shortcut available once
the field had been put in the wrong place — and it introduced a single mutable field shared
across every reference, in the one component whose entire purpose is to keep payments
independent of one another. Two submissions resolving different scenarios would race, and
the token endpoint would answer with whichever landed last. That defeats test isolation
precisely where the rest of this design goes out of its way to allow parallelism, down to
choosing a `DeferredResult` over a blocking sleep.

The token belongs to the session, not to the payment. It is now declared with the rule set,
and `Scenario` no longer carries it.

Worth recording rather than quietly editing: the error was in the design document, not in
the implementation, and it was the implementation that revealed it.

## Alternatives rejected

**A scenario as a response mapping.** Cannot express any sequence, which is most of them.

**Magic values only.** Frozen in code, not extensible by a contributor, and unable to
express a per-test behaviour.

**One scenario per instance, set at startup.** Requires a container per test case, which is
slow in CI and cannot test two behaviours concurrently.
