# The first ten issues

All ten live in the `simulator` module. This is deliberate: they need no operator
account, no credentials, and no knowledge of double-entry bookkeeping — only the ability
to make a fake server behave badly on command. They are the most accessible contributions
this project will ever offer, and each one directly enables a conformance test.

Suggested labels: `good first issue`, `simulator`, plus `help wanted` on all of them.

---

### 1. Scaffold the simulator as a runnable service

Stand up `simulator` as a minimal Spring Boot application exposing the MTN MoMo
Collections surface: `POST /collection/v1_0/requesttopay`, `GET
/collection/v1_0/requesttopay/{referenceId}`, and the token endpoints. In-memory state,
no persistence. `mvn spring-boot:run` must start it on a documented port.

**Done when** a `curl` request to `requesttopay` returns 202 and the reference can be
queried back.

---

### 2. Scenario: the happy path

A submitted payment resolves to `SUCCESSFUL` on the next query. This is the baseline
every other scenario deviates from, so it defines the scenario mechanism itself: how a
scenario is selected, and how the simulator is told which one to play.

**Done when** the scenario can be chosen per reference and the happy path is the default.

---

### 3. Scenario: configurable latency

Delay any response by a declared duration, per endpoint. Needed to test client timeouts
without waiting on a real network.

**Done when** a scenario can specify e.g. 3 s on submit and 200 ms on query.

---

### 4. Scenario: timeout, then late success

The submit call never answers — the connection hangs and is closed by the client — but
the payment *did* go through and a later query returns `SUCCESSFUL`.

This is the single most important scenario in the project. It is the case that turns a
gateway that guesses into a gateway that asks.

**Done when** a client that times out on submit can still resolve the payment by query.

---

### 5. Scenario: duplicate callback

Deliver the same callback twice, with identical content, a configurable interval apart.

**Done when** the number of deliveries and the interval are both configurable.

---

### 6. Scenario: callback before the submit response

Deliver the callback *before* the submit call returns. This genuinely happens, and it
breaks any implementation that assumes it knows the reference only after submit returns.

**Done when** the ordering is deterministic and reproducible in a test.

---

### 7. Scenario: callback for an unknown reference

Send a well-formed, correctly signed callback for a reference the gateway has never
seen — a stray retry from another environment, or an attack.

**Done when** the simulator can emit a callback for an arbitrary reference on demand.

---

### 8. Scenario: status flapping

Return `SUCCESSFUL` on one query and `FAILED` on the next for the same reference.

No terminal state may ever be reopened. This scenario is how we prove it.

**Done when** the sequence of returned statuses is scriptable per reference.

---

### 9. Scenario: token expiry mid-flight

Issue a bearer token with a very short life and reject subsequent calls with 401 once it
expires, requiring the client to renew and retry with the same reference.

**Done when** token lifetime is configurable down to a couple of seconds.

---

### 10. Scenarios as declarative files

Load scenarios from a YAML file instead of code, so a contributor can add a failure mode
without writing Java, and so a project using Nkap can commit its own scenarios alongside
its tests.

**Done when** issues 2 through 9 are all expressible in YAML and the built-in ones are
loaded from files.

---

### Bonus, once the above land

`docker compose up` starts the simulator and the gateway together and runs one payment
end to end. This is the v0.1 acceptance test, and the first thing a newcomer will try.
