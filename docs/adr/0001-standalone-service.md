# ADR 0001 — Nkap is a standalone service, with a dependency-free core

- **Status:** accepted
- **Date:** 2026-09-07

## Context

Nkap could be shipped in three shapes: a Java library applications embed, a standalone
service they call over HTTP, or a core library wrapped by a service.

The library is the smallest thing to build. It is also the shape a Java-first author
reaches for by reflex, which is reason enough to examine it carefully.

## Decision

**A standalone service**, deployable as one container, exposing an HTTP API and outgoing
webhooks — with a strict internal split into Maven modules, where `core` is pure domain
code with no web, Spring, or persistence dependency.

## Rationale

**The audience does not write Java.** The developers who integrate mobile money across
Africa work mostly in PHP, JavaScript and Python. A Java library is unusable to them,
which caps both adoption and the contributor pool at a fraction of the addressable
community.

**A ledger cannot be a guest.** The accounting invariants — append-only, zero-sum, no
implicit conversion — can only be guaranteed by a component that owns its own database.
Embedded as a dependency inside someone else's application, sharing their transaction
manager and their schema migrations, the ledger becomes a suggestion. The whole value
proposition of this project is that it is not a suggestion.

**Operational reality.** Credential rotation, provider outages, reconciliation schedules
and webhook retries are operational concerns with their own lifecycle. Inside a host
application they are that application's problem, badly.

## Consequences

- Integrators pay a network hop and must run one more service. Accepted: the alternative
  costs them correctness.
- The dependency-free `core` keeps the option of publishing a library later, at no
  additional design cost today.
- Accounting invariants can be tested in milliseconds, without a container or a database.
  This is what makes an outside contribution touching the ledger reviewable at all.
- The service must be genuinely easy to run, or the network hop becomes an adoption
  barrier. `docker compose up` starting the gateway and the simulator together is
  therefore a v0.1 requirement, not a nicety.

## Alternatives rejected

**Library only.** Caps the audience to Java and cannot guarantee the ledger's invariants.

**Core library plus service, both published from day one.** The same end state as this
decision, but paying the API-design cost of a public library before a single user exists.
The module split preserves the option without the cost.
