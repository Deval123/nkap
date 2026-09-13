# ADR 0009 — Float and suspense accounts are per installation, not per operator

- **Status:** accepted
- **Date:** 2026-09-13

## Context

Issue #82 lets one deployment configure several MTN country installations at once — Cameroon,
Ghana, and however many more a merchant needs — each its own `ProviderAdapter` with its own
`ProviderId` (`mtn-cm`, `mtn-gh`; see the pull request for why an installation is a whole
adapter rather than one `mtn` adapter routing between several).

`AccountId.providerFloat` and `AccountId.suspense` already take a provider id string, not a
fixed `"mtn"`. Whether that string should be the operator (`mtn`) or the installation
(`mtn-cm`) was not yet decided, and the two currency-sharing installations MTN actually has —
Cameroon and the Republic of Congo both settle XAF — are exactly the case that makes the
difference visible: with a single `mtn` account, both would post to `provider:mtn:float:XAF`.

## Decision

**The provider id in `provider:<id>:float:<CCY>` and `suspense:<id>:<CCY>` is the
installation's own `ProviderId`, never a shared operator name.** Cameroon posts to
`provider:mtn-cm:float:XAF`; Congo posts to `provider:mtn-cg:float:XAF`. Two accounts, not
one, because they are two accounts at MTN: separate developer-portal registrations, separate
float balances, reconciled against two separate statements.

**`merchant:<id>:payable` stays keyed by currency alone, never by installation.** A merchant
collecting in both Cameroon and Congo is owed one XAF sum. Splitting it into
`merchant:acme:payable:mtn-cm:XAF` and `merchant:acme:payable:mtn-cg:XAF` would invent a
distinction the merchant does not have — they see one currency balance, not one per
installation that happens to share it.

Nothing about the entries themselves changes shape: [ADR 0006](0006-what-a-settled-collection-posts.md)
and [ADR 0007](0007-what-a-settled-disbursement-posts.md) still decide what a settled
collection or disbursement posts, gross, two postings, summing to zero. This ADR decides
only which string names the float side.

## Rationale

**A ledger that cannot be reconciled is not a ledger.** `provider:mtn:float:XAF` shared
between Cameroon and Congo would be one balance standing in for two real accounts at the
operator. A statement import for Cameroon would move a number that is partly Congo's, and no
comparison against either operator's actual statement could ever agree with it — not because
of a bug, but because the account itself no longer corresponds to anything MTN can show a
human.

**The installation already is the unit of everything else.** Credentials, base URL, target
environment and currency are all per installation (`MtnProfile`); the adapter identity
(`ProviderId`) is per installation; `Payment.provider()` already records which installation
handled it. Making the float and suspense accounts per operator instead would be the one
place this slice reintroduced a shared identity everything else had already moved past.

**The merchant payable side has no equivalent reason to split.** A merchant's relationship
is with Nkap, not with MTN's per-country registrations — nothing about how Nkap owes a
merchant money depends on which installation collected it, only on what currency it is in.

## Consequences

- `AccountId.providerFloat` and `AccountId.suspense` needed no signature change — they
  already took a plain provider id string; every caller now passes the installation's
  `ProviderId.toString()` rather than a constant, which is what "the installation is its own
  adapter, with its own id" already implied.
- `SettlementService`, the reconciler and the statement importer needed no change either:
  they already read the provider id off the payment (or, for statements, take it as an
  argument) rather than hardcoding one.
- A statement import must be run per installation, against that installation's own
  statement — this was already implicit in per-country credentials, and is now explicit in
  the accounts it reconciles against.
- `SuspenseBalanceMetrics` and its Prometheus gauge already iterate one `ProviderRouting` per
  installation (`docs/prometheus-alerts.yml`'s suspense-balance alert), so a stuck suspense
  account in one country pages distinctly from one in another, rather than being averaged
  away inside a shared total.

## Alternatives rejected

**One `provider:mtn:float:<CCY>` account, summed across every installation that settles that
currency.** Reads as a single "how much MTN owes Nkap in XAF" number, which sounds
convenient until a discrepancy shows up: nothing about that number says whether it is
Cameroon's statement that is short or Congo's. A convenience that turns every investigation
into an extra join is not a convenience.

**Key `merchant:<id>:payable` by installation too, for symmetry with the float side.**
Symmetry is not the goal; correctness is, and the two sides answer different questions. The
float side is "what does Nkap hold at each of MTN's country registrations" — genuinely
per-installation. The payable side is "what does Nkap owe this merchant" — genuinely per
currency, and per-installation would be a distinction invented for the ledger's convenience,
not one the merchant's own accounting has.
