# provider-mtn

The MTN MoMo adapter — the first implementation of `ProviderAdapter`.

Notes gathered so far, to be verified against a live sandbox:

- **Auth.** An `Ocp-Apim-Subscription-Key`, plus an API user and API key exchanged for a
  short-lived bearer token. Cache the token and renew it before expiry, not on the first
  401.
- **Submission.** `POST /collection/v1_0/requesttopay` with `X-Reference-Id` and
  `X-Target-Environment`. Returns 202 with no body — the outcome comes later.
- **`X-Reference-Id` is the idempotency key.** A retry reuses the same value, never a new
  one. This is why `ReferenceId` is generated and persisted before the call.
- **Outcome.** Poll `/requesttopay/{referenceId}`, and optionally receive a callback via
  `X-Callback-Url`. Both paths converge on the same state transition, and a callback is
  never believed on its own — it triggers a confirming query.
- **Statuses.** `PENDING`, `SUCCESSFUL`, `FAILED`. Anything else is `UNKNOWN`, never a
  failure.
