# Working on Nkap

Conventions for anyone — human or assistant — writing code in this repository.
Read `README.md` for what the project is, `CONTRIBUTING.md` for how it is governed, and
`docs/adr/` for why it is shaped this way.

## Language

**Everything committed to this repository is in English**: code, comments, Javadoc,
commit messages, documentation, issues, pull requests, and release notes.

The maintainer speaks French and conversations may well happen in French. That changes
nothing here. This is an open source project aimed at contributors across Africa and
beyond; a bilingual history would be a barrier, and it is the kind of inconsistency that
never gets fixed afterwards. If you are asked in French to write a commit, the commit is
still in English.

## Commits

- **Never add assistant attribution.** No `Co-Authored-By` trailer naming a tool, no
  session link, no "generated with" line. Commits carry the repository author's name and
  nothing else.
- Sign off: `git commit -s`.
- Subject in the imperative, under 72 characters, then a blank line, then a body that
  explains **why** — the diff already says what.
- One concern per commit.

## The four rules that do not bend

These are enforced by tests, and a change that breaks one of them is a bug in the change,
not in the test.

1. **The ledger is append-only.** No `UPDATE`, no `DELETE` on entries. A mistake is
   corrected by a reversing entry, so the original stays in the record.
2. **A timeout is never a failure.** No code path may conclude that a payment failed
   without an explicit statement from the operator. A missing, unreadable or unrecognised
   response maps to `UNKNOWN`, which is not terminal.
3. **`core` has no dependencies.** No Spring, no web, no JSON library, no ORM. If a change
   to `core` needs a dependency, the change belongs in another module. `provider-api`
   depends only on `core`.
4. **Money is an integer count of minor units.** No `double`, no `float`, one currency per
   ledger entry, and no currency conversion outside a position account. XAF and XOF have
   zero minor units — code that assumes two decimals everywhere is wrong.

## Module boundaries

| Module | Contains | Depends on |
| --- | --- | --- |
| `core` | ledger, payment state machine, idempotency | nothing |
| `provider-api` | the `ProviderAdapter` contract | `core` |
| `provider-mtn` | the MTN MoMo adapter | `provider-api` |
| `conformance` | the kit every adapter must pass | `provider-api` |
| `simulator` | scriptable fake operator (Spring Boot) | — |
| `server` | REST, webhooks, outbox, schedulers (Spring Boot) | `provider-api` |

An adapter translates; it never decides. State transitions, idempotency and bookkeeping
live in `core` so that adding an operator cannot introduce a bug in any of them.

Spring Boot is imported as a BOM in the root POM, never as a parent — that is what keeps
`core` free of Spring.

## Build

```bash
mvn -B clean verify              # everything
mvn -B -pl core test             # the invariants, in milliseconds
mvn -B -pl simulator spring-boot:run   # the fake operator, on port 8081
```

## Tests

Test names are sentences stating the rule being defended, in `@DisplayName`:
`a timeout moves a payment to UNKNOWN, never to FAILED`. Not method names.

In `core`, the test comes first.

## Versioning

Annotated tags `vX.Y.Z`, a tag means a GitHub release, and untagged `main` is the normal
state of this project. Do not create tags or releases as a side effect of another task.
See the versioning section of `CONTRIBUTING.md`.
