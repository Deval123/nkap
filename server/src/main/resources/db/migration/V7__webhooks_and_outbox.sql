-- The transactional outbox (ADR 0003) and the webhook endpoints it delivers to. Roadmap
-- §3's outbox and §4's webhook line, in one migration because the outbox exists to
-- deliver the webhooks (issue #77).
--
-- outbox_event: one row per notification-worthy state change, written in the SAME
-- transaction as the state change itself -- see OutboxNotifier, called from
-- SettlementService and PaymentService while their own transaction is still open. Not
-- append-only: delivery is retried, so attempts / next_attempt_at / delivered_at /
-- dead_lettered_at move as OutboxRelay works the row. The payload, once written, never
-- changes -- a replay resends it, it does not rebuild it.
--
-- webhook_endpoint: where a merchant receives its events, and the HMAC secret used to
-- sign them. Unlike api_key, the secret is stored readable, not hashed: signing has to
-- *use* the secret, not just compare it (see WebhookSecrets, and the paragraph this adds
-- to docs/positioning.md). One endpoint per merchant in this slice -- several is later.
--
-- Hand-written inverse: db/rollback/V7__webhooks_and_outbox.sql, exercised by
-- MigrationRollbackIT.

CREATE TABLE webhook_endpoint (
    id           uuid        PRIMARY KEY,
    merchant_id  text        NOT NULL UNIQUE,
    url          text        NOT NULL,
    secret       text        NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT webhook_endpoint_merchant_not_blank CHECK (length(trim(merchant_id)) > 0),
    CONSTRAINT webhook_endpoint_url_not_blank CHECK (length(trim(url)) > 0)
);

CREATE TABLE outbox_event (
    id                uuid        PRIMARY KEY,
    merchant_id       text        NOT NULL,
    event_type        text        NOT NULL,
    payload           text        NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    attempts          integer     NOT NULL DEFAULT 0,
    next_attempt_at   timestamptz NOT NULL,
    delivered_at      timestamptz,
    dead_lettered_at  timestamptz,
    last_error        text        NOT NULL DEFAULT ''
);

-- The relay's claim query: due, not yet delivered, not dead-lettered, oldest first. A
-- partial index on exactly that predicate is what keeps the claim an index range scan
-- instead of a growing table scan as delivered rows pile up.
CREATE INDEX outbox_event_due_idx ON outbox_event (next_attempt_at)
    WHERE delivered_at IS NULL AND dead_lettered_at IS NULL;

CREATE INDEX outbox_event_merchant_idx ON outbox_event (merchant_id);
