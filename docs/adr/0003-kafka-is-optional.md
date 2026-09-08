# ADR 0003 — The outbox lives in PostgreSQL; Kafka is optional

- **Status:** accepted
- **Date:** 2026-09-08

## Context

Every state change in Nkap must reach the outside world exactly once: a webhook to the
merchant, a read projection, an audit trail. The failure mode to design against is the
one that follows the timeout in frequency — *the state changed but nobody was told* —
which happens whenever a system commits a database transaction and then publishes to a
broker as a second, unprotected step.

The transactional outbox pattern solves it: the event is written to a table inside the
same transaction as the state change, and a relay publishes it afterwards. The open
question is what the relay publishes *to*, and whether that thing is mandatory.

Kafka is the author's home ground and the obvious answer at scale.

## Decision

**The outbox table is in PostgreSQL, and the relay is in-process. Kafka is an optional
connector, off by default.**

A default Nkap deployment is one container plus one PostgreSQL database. It delivers
signed webhooks, keeps its audit trail, and retries failures without any broker. Operators
who already run Kafka enable a connector and get every domain event on a topic; operators
who do not are never asked to learn what a consumer group is.

## Rationale

**The target user runs a shop, not a platform team.** The developers this project exists
for integrate mobile money for merchants, cooperatives and small fintechs across Africa.
Requiring a Kafka cluster before the first payment can be tested does not make the project
more serious; it makes it unusable for most of the people it was written for. That cost is
invisible in the issue tracker — nobody files "I gave up during setup" — which is exactly
why it has to be decided deliberately rather than by default.

**The correctness guarantee comes from the outbox, not from the broker.** The property
worth protecting is that the state change and its event commit or fail together. A
PostgreSQL table inside the same transaction gives that in full. Kafka adds throughput,
fan-out and replay to other systems — real benefits, none of which is correctness.

**Optionality costs almost nothing here.** The relay reads the outbox and hands events to
one or more publishers. A webhook publisher and a Kafka publisher are two implementations
of the same interface. Building the second later is additive.

**One database is also one backup, one restore drill and one failure mode.** For a system
whose entire pitch is auditability, being restorable by someone who is not a distributed
systems engineer is part of the product.

## Consequences

- `docker compose up` starts a gateway and a PostgreSQL database. Nothing else is
  required for a complete, working installation.
- The outbox relay is at-least-once. Every consumer contract — webhooks included — must
  say plainly that handlers have to be idempotent.
- Outbox rows are retained and pruned on a documented schedule; they are the audit trail
  until something else consumes them.
- The Kafka connector must not become a second code path through the domain. It publishes
  the same events the relay already produces, or it is wrong.
- Throughput is bounded by the relay polling the outbox. That is a real limit and an
  acceptable one at the scale this project starts at; raising it is an optimisation, not a
  redesign.

## Alternatives rejected

**Kafka mandatory.** The cleanest model at scale, and the wrong first impression. It sets
the price of trying the project above what most of its intended users will pay.

**No broker at all, ever.** Closes the door on the event-driven integrations larger
adopters will ask for, and discards the author's strongest ground for no gain.

**Publish directly to the broker from the transaction.** The bug this ADR exists to
prevent. A database transaction and a network publish cannot commit together.
