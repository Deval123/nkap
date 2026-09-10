-- Caller authentication: an API key per merchant backend, plus the admin flag that gates
-- operator-wide data (the statement report). Roadmap section 3.
--
-- Only the SHA-256 hash of the key is stored, never the key. A generated key already
-- carries 256 bits of entropy, so there is nothing for a slow KDF to defend against -- no
-- low-entropy password to make expensive to guess -- and a fast hash is the right tool.
-- See ApiKeys for the same note in code, so the next reader does not wonder whether bcrypt
-- was forgotten.
--
-- Not append-only: last_used_at is updated on every authenticated request, and revoking a
-- key is deleting its row (rotation beyond that is a later slice). No trigger.
--
-- Hand-written inverse: db/rollback/V6__api_keys.sql, exercised by MigrationRollbackIT.

CREATE TABLE api_key (
    id            uuid        PRIMARY KEY,
    token_sha256  text        NOT NULL UNIQUE,
    merchant_id   text        NOT NULL,
    is_admin      boolean     NOT NULL DEFAULT false,
    label         text        NOT NULL DEFAULT '',
    created_at    timestamptz NOT NULL DEFAULT now(),
    last_used_at  timestamptz,
    CONSTRAINT api_key_merchant_not_blank CHECK (length(trim(merchant_id)) > 0),
    CONSTRAINT api_key_hash_is_sha256 CHECK (token_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX api_key_merchant_idx ON api_key (merchant_id);
