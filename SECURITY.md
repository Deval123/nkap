# Security policy

## Reporting a vulnerability

Do not open a public issue, a pull request, or a discussion. Write to
**security@nkap.dev**. Say what you found, how to reproduce it, and what you think an
attacker gets from it. No template, no form — those three things are enough.

## What to expect, and when

You will get an acknowledgement within 72 hours.

Beyond that, there is no committed timeline for a fix. Nkap has one maintainer, and a
promised fix date would be a promise nobody is in a position to keep. What is promised
instead: you will be told where things stand, rather than left to wonder.

## Supported versions

A fix lands on `main` and ships in the next tagged release cut from it. Nothing is
backported — not because backporting was weighed and rejected, but because no maintenance
branch has ever existed for this project. There is no supported-versions table here, because
a table would list guarantees this project cannot honour.

## Disclosure

Please hold public disclosure until a release carries the fix, or 90 days from your report,
whichever comes first.

If you want credit, it goes in the release notes for the version that carries the fix. If you
don't ask for it, you are not named.

## Bounty

There is no bounty. This project has no budget for one.

## Before you report: read this first

[`docs/security-notes.md`](docs/security-notes.md) names, by name, several things Nkap
deliberately does not protect against (§3) and several things it leaves entirely to the
operator (§4). Read it first, so you do not spend your time reporting something already
written down as a known, deliberate limit. Three you are most likely to hit:

- The **management port** (`management.server.port`) is unauthenticated by design, and is
  meant to stay off the public network — unpublished by every compose file in this
  repository, behind a proxy or on an internal network only.
- The **webhook signing secret** is stored readable, not hashed, because HMAC needs the raw
  bytes to sign a delivery — whoever can read that table can forge a notification.
- There is **no rate limiting** — that is a reverse proxy's job, not this project's.

This is not a request to stay quiet about that space. If you find that one of those
assumptions does not hold — for example, a compose file that publishes the management port
instead of keeping it internal — that is a vulnerability, and we want to hear about it.
