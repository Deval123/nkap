# ADR 0012 — Nkap stays Apache 2.0, with no CLA, now that the license is no longer one person's to change

- **Status:** accepted
- **Date:** 2026-09-19

## Context

**This is the first ADR in this repository that is not about code.** Every other one records
a contract shape or a state-machine rule, each defensible by a test. This one is not, and it
still belongs here, for a reason specific to what it decides rather than to precedent: a
license can only be changed unilaterally while one person holds the copyright to everything it
covers. **That stopped being true on 2026-09-08**, when PR #21 merged Cid-oe's bounded-eviction
fix for a memory leak in the simulator's callback-attempt storage (closing #16) — the first
outside contribution to land on `main`, six days before `v1.0.0` was tagged. From that commit
on, Cid-oe holds the copyright to their part of this codebase, and changing the license needs
their agreement too — not this project's alone, and not retroactively. Three more outside
contributions have landed since. Thirty contributors from now, changing it will not be a
decision anyone can make; it will be a negotiation with everyone who has ever sent a pull
request, and in practice it will not happen. **This ADR is not written while a window is still
open — the window closed before it was written. It records which license `main` was already
carrying past the point of no return, deliberately, rather than by default.** That the choice
was already made by events rather than by this document is not a reason to skip writing it
down; it is the stronger one. No other file in this repository says so, because no other file
is read for what was still possible on the day it was written.

The reason recording it here matters is `docs/positioning.md`'s own bet: Nkap's entire
strategy is that adapters — the part of this project an aggregator does not need to write
itself — are written by people who are not its author. The conformance kit exists so that work
can be reviewed without an account with the operator it targets. Whatever this project asks of
a contributor sits directly in the path of that bet succeeding or not.

## The decision

**Nkap stays Apache License 2.0.** No CLA. Contributions are accepted under the Developer
Certificate of Origin (DCO), which this project already practices more than it has written
down: three of the four outside commits merged so far carry `Signed-off-by`. The fourth — PR
#79, "Add configurable MTN simulator error codes", closing #26 — does not; its own description
says `Closes #26`, but nothing at merge time checked for the trailer, and nothing caught its
absence. An unwritten rule is a rule nobody is checking, and this is what that looks like in
practice — the argument for writing it down, not evidence it was never needed.

## Reasoning

**For it.** This is not a forecast: four outside contributions have already landed under
exactly this model — no CLA, a DCO certification — closing labelled issues nobody on this
project opened for themselves: #16 (`good first issue`, a bounded-eviction fix for a memory
leak in the simulator's callback-attempt storage), #4 (`good first issue`, tests for
configurable simulator latency), #51 (`enhancement`, three core follow-ups), and #26
(`good first issue`, MTN-shaped simulator error bodies). `docs/positioning.md`'s bet — that
adapters get written by people who are not this project's author — is not a bet this ADR is
asking anyone to place; it already paid out, before this ADR existed to record it. A CLA is
friction placed directly on the door that already produced those four contributions: a
contributor who signs a certificate about the commit they are already making is a different,
and much smaller, ask than one who assigns rights to a document a lawyer has to read first.
Apache 2.0 over MIT was already the right
permissive choice, and not a new question this ADR reopens: its explicit patent grant matters
to anyone evaluating this for a payment path, which `README.md`'s own License section already
says. AGPL was also available and is rejected here, for a reason spelled out below rather than
dismissed in passing.

**What this forecloses, stated plainly.** Selling license exemptions — dual licensing, the
model where a permissive default funds a paid alternative for anyone who cannot accept its
terms — is off the table the moment an outside contribution is merged, because the project no
longer holds every right needed to offer one. This decision gives that model up, deliberately,
in exchange for the contribution model `docs/positioning.md` bets on instead. **It does not
foreclose** support contracts, hosting Nkap as a service, consulting, or sponsorship: the
license constrains what someone else may do with the code, never what the copyright holder may
do with their own.

**The argument against, and it is not theoretical for this project.** AGPL would stop a
commercial actor from building a product on Nkap and giving nothing back — network use would
trigger the copyleft, unlike ordinary GPL. For a generic developer tool that risk is remote
enough to ignore. For a mobile money gateway aimed at Africa, it is not: commercial
aggregators already sell unified access to these same operators (`docs/positioning.md`'s own
comparison — Payfonte, Simiz, Kollekt, Zirzir), and a double-entry ledger with a conformance
kit already built and already tested is months of their own work they would not have to do.
**This ADR accepts that risk rather than dismissing it**, because the alternative — AGPL, to
foreclose it — would also foreclose the contribution model the whole project is betting its
success on, measured by `docs/positioning.md`'s own metric: applications running Nkap that its
author did not set up. A license that protects against the risk this paragraph names is a
license that also stops the adoption the project needs to matter at all.

**What actually protects the project under a permissive license is not the license: it is the
name.** Apache 2.0 §6 grants no trademark rights — anyone may fork this code under the terms
above, and nobody who does may call their fork Nkap or use `nkap.dev`. `NOTICE` now says so, in
one line, alongside the copyright notice it already carried.

**The limit of that, stated honestly rather than implied away.** An unregistered name is a
weaker claim than a registered mark, and how much weaker depends on the jurisdiction asking.
This ADR is not legal advice, and whether to register anything — a trademark, in which
countries, under whose name — is a separate decision this ADR does not make and does not
imply has already been made.

## Consequences

- `LICENSE` does not change. Nothing about what a user of Nkap is permitted to do — run it,
  modify it, embed it in a commercial product, distribute it — changes from what Apache 2.0
  already grants.
- A contributor signs their commits off (`git commit -s`) and nothing else. No CLA to sign, no
  copyright to assign. `CONTRIBUTING.md` says this in the same place a contributor already
  reads before their first pull request.
- The project cannot relicense unilaterally once outside contributions exist, because it will
  no longer hold every right a relicense needs. That is this decision's whole point, not a
  side effect discovered later.
- Dual licensing is not a future option for this project's own code. Support, hosting,
  consulting and sponsorship remain open; they were never licensing questions.
- Nobody may call a fork of this code Nkap, or use `nkap.dev` for one, on trademark grounds
  independent of the license — recorded in `NOTICE`, not enforced by anything in `LICENSE`.
- Whether "Nkap" is ever formally registered as a trademark is undecided, and this ADR takes
  no position on it beyond naming that the question exists and is unresolved.

## Alternatives rejected

**AGPL.** Rejected above, as a live risk accepted rather than a dismissed option: it would
close the exact gap this ADR is most worried about — a commercial aggregator building on Nkap
without contributing back — at the cost of the adoption `docs/positioning.md` measures success
by. A license chosen to prevent the risk this project is trying hardest to invite is the wrong
trade for what this project is betting on.

**A CLA, without changing the license model.** Would not itself change what this project may
do with contributions, only make contributing harder for the person this project most needs to
attract — friction on the one door `docs/positioning.md` says the whole strategy depends on,
for a benefit (relicensing flexibility) this decision has already chosen not to keep.

**Registering "Nkap" as a trademark now, alongside this ADR.** Considered and left undone,
deliberately distinct from deciding not to: this ADR records a licensing decision within one
person's power to make today; a trademark filing is a different kind of commitment, in a
specific jurisdiction, and bundling it here would let a decision this ADR is not equipped to
make ride in on one that it is.
