# Positioning

Where Nkap sits, and why it is not one of the projects it resembles.

This file exists because two questions come up whenever the project is shown to someone
who knows this space, and an answer that lives only in a conversation is an answer nobody
can find.

## The one sentence

Nkap is a self-hosted gateway that a developer puts between their application and a mobile
money operator, so that the integration has an accounting record that can be proved, a
payment state machine that never guesses, and a way to test the failures that actually
happen.

It is not a switch between institutions, not a payment service provider, and not a
custodian. Money stays at the operator. Nkap records.

## Why not another unified API

Several products already put a single API in front of several African operators —
Payfonte, Simiz, Kollekt commercially; Zirzir and others as open source. The market they
address is real, and Nkap is not trying to out-integrate them on operator count.

The claim here is different. Most of that work answers *"connect to more providers."*
Nkap answers *"prove that what you connected to behaves correctly when it misbehaves."*
That is why the parts of this repository that took the longest are the parts an aggregator
does not need: an operator simulator whose scenarios are timelines, a conformance kit an
adapter has to pass before it is merged, a state machine where a timeout is
`UNKNOWN` rather than `FAILED`, and a ledger whose invariants are enforced by the database
rather than by the application that writes to it.

An operator count is copied in a quarter. A contract, its test kit and the adapters that
pass it are not.

## Why not Mojaloop

[Mojaloop](https://mojaloop.io/) is open-source interoperability infrastructure: a switch
that lets licensed institutions — banks, mobile money operators, payment providers — settle
with each other, deployed by central banks and national schemes.

It sits above Nkap, not beside it. Mojaloop's participants are institutions; Nkap's user is
a developer with an MTN subscription key who needs their application to survive a dropped
connection. A team that needs a national scheme should use Mojaloop. A team that needs to
take a payment from an MTN wallet this month should not be asked to stand one up.

If Nkap is ever deployed in a market where Mojaloop is the rail, Mojaloop becomes another
`ProviderAdapter`, and it will have to pass the same conformance kit as MTN.

## Why not build the ledger on Blnk or TigerBeetle

This is the sharper question. [Blnk](https://github.com/blnkfinance/blnk) and TigerBeetle
are open-source double-entry ledgers, well built, and either could store entries.

The answer is that storing entries is the part of this problem that is already solved. What
is not solved is the join between the ledger and the payment: **which states may write, and
which must not.** In Nkap, `SUCCEEDED` writes exactly one entry and `UNKNOWN` writes
nothing, an entry id is derived from the payment reference so a repeated settlement is
refused by the ledger itself, and the reconciler's job is defined as driving the suspense
account back to zero. Those rules are the product. A general-purpose ledger would hold the
rows and leave every one of those decisions to the code above it — which is where they get
made inconsistently, once per team.

The ledger here is deliberately small: append-only, zero-sum, integer minor units, one
currency per entry, corrections by reversal. If it grows into something that wants
sharding, high throughput or an account model of its own, putting it on a dedicated engine
becomes the right move, and the interface it hides behind (`Ledger`, in `core`) exists so
that day costs one implementation rather than a rewrite.

## What is not in scope, and why it is written down

Nkap does not hold funds, does not intend to become a licensed payment institution, and
does not do KYC, AML or sanctions screening. It does not convert between currencies —
several currencies are supported, conversion is not, because conversion needs a rate, a
date, a position account and a revaluation policy, and a first release that invents any of
those is a ledger that lies.

**The gateway authenticates nothing.** There is no login, no API key, no request signing.
The callback endpoint is unauthenticated by necessity — the operator sends no credential —
and safe because it only ever triggers a confirming query; every other endpoint is
unauthenticated too, and the deployment is expected to sit behind whatever the operator of
the system provides: a reverse proxy, a private network, an mTLS gateway. This is why the
one action that writes to the ledger from a file — statement reconciliation — is a
host-side command and not a route. Endpoint authentication is its own slice; until then, a
reader deciding whether to expose this service should assume anyone who can reach it can
call it.

These exclusions are not modesty. They are what keeps the project legally simple enough for
one person to run and small enough to finish.

## How success is measured

Not stars. The number of applications running Nkap that the author did not set up.

Until that number is above zero, this is a well-tested piece of software rather than
infrastructure, and the roadmap's order reflects it: MTN working end to end, a simulator and
a conformance kit anyone can run, documentation someone can follow without asking, then a
second operator — contributed, and passing the kit.
