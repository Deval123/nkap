# conformance

The test kit every `ProviderAdapter` must pass before it is merged.

This module is what makes the project governable beyond its author: it lets a maintainer
accept an adapter for an operator they have no account with. If the kit passes, the
adapter is acceptable.

Cases the kit must cover:

1. The same reference submitted twice produces one payment, not two.
2. A duplicate callback produces one state transition, not two.
3. A callback that arrives before the submit response is not lost.
4. A callback for an unknown reference is rejected without writing anything.
5. A timeout followed by a successful query resolves to `SUCCEEDED`, never `FAILED`.
6. A status that flaps between values never reopens a terminal payment.
7. An expired token mid-flight is renewed and the call retried with the same reference.
