# simulator

A fake mobile money operator that misbehaves on command.

It exposes the MTN MoMo API surface and plays a declared scenario: latency, timeout then
late success, a callback delivered twice, a callback delivered before the submit
response, a status that flips from `SUCCESSFUL` to `FAILED`, a token that expires
mid-flight.

It serves the project's own test suite first. But it has a strategic property: **it is
useful on its own.** A developer who will never run Nkap still wants a reliable fake MoMo
to test their integration against. It is therefore the front door of this project — and
each new scenario is an isolated, testable, low-risk pull request.

This is why the simulator is built *first*, not last.
