# server

The deployable service: REST API, incoming and outgoing webhooks, transactional outbox,
reconciliation schedulers.

Design notes that belong here rather than in `core`:

- Every state transition writes a domain event to an **outbox table in the same database
  transaction** as the change itself, then a relay publishes to Kafka. This makes the
  case "the state changed but nobody was told" impossible — the second-largest source of
  payment bugs after the timeout.
- Outgoing webhooks are signed (HMAC-SHA256, timestamp included to block replay),
  delivered at least once, with exponential backoff and a dead-letter queue. The
  documentation tells consumers plainly: **your handler must be idempotent.**
