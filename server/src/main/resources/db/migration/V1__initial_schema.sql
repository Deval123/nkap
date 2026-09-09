-- The Nkap gateway's state: the idempotency store, payments and their history, and the
-- ledger. The invariants live here as constraints and triggers, not only in Java, because
-- a rule enforced only in the application is a rule a migration script walks straight
-- through.
--
-- Amounts are BIGINT minor units everywhere. Never NUMERIC, never a floating type: Money
-- is an integer count and the column is too.
--
-- The hand-written inverse of this migration is db/rollback/V1__initial_schema.sql,
-- exercised by MigrationRollbackIT. Flyway's own undo is a paid feature.

-- ------------------------------------------------------------------------------------
-- Append-only guard, shared by the ledger tables and the payment history. A mistake is
-- corrected by a compensating row, never by an UPDATE or a DELETE.
-- ------------------------------------------------------------------------------------

CREATE FUNCTION nkap_forbid_mutation() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'relation "%" is append-only: % is refused (correct a mistake with a compensating row)',
        TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;

-- ------------------------------------------------------------------------------------
-- Idempotency store. Not append-only: begin() inserts the claim, complete() fills in the
-- response, abandon() deletes a claim that was never answered.
-- ------------------------------------------------------------------------------------

CREATE TABLE idempotency_record (
    merchant_id         text        NOT NULL,
    idempotency_key     text        NOT NULL,
    fingerprint_sha256  text        NOT NULL,
    response            text,
    created_at          timestamptz NOT NULL DEFAULT now(),
    completed_at        timestamptz,
    PRIMARY KEY (merchant_id, idempotency_key)
);

-- ------------------------------------------------------------------------------------
-- Payments and their history. The payment row is mutable (its state moves); the history
-- is append-only.
-- ------------------------------------------------------------------------------------

CREATE TABLE payment (
    reference               uuid        PRIMARY KEY,
    provider                text        NOT NULL,
    merchant_id             text        NOT NULL,
    operation               text        NOT NULL,
    amount_minor            bigint      NOT NULL,
    currency                text        NOT NULL,
    counterparty_msisdn     text        NOT NULL,
    payer_message           text        NOT NULL DEFAULT '',
    payee_note              text        NOT NULL DEFAULT '',
    provider_options        text        NOT NULL DEFAULT '{}',
    state                   text        NOT NULL,
    provider_reference      text        NOT NULL DEFAULT '',
    provider_transaction_id text        NOT NULL DEFAULT '',
    created_at              timestamptz NOT NULL,
    updated_at              timestamptz NOT NULL,
    CONSTRAINT payment_amount_positive CHECK (amount_minor > 0)
);

CREATE TABLE payment_transition (
    payment_reference   uuid        NOT NULL REFERENCES payment (reference),
    seq                 integer     NOT NULL,
    from_state          text        NOT NULL,
    to_state            text        NOT NULL,
    occurred_at         timestamptz NOT NULL,
    cause               text        NOT NULL,
    operator_code       text        NOT NULL DEFAULT '',
    note                text        NOT NULL DEFAULT '',
    raw_response        text        NOT NULL DEFAULT '',
    PRIMARY KEY (payment_reference, seq)
);

CREATE TRIGGER payment_transition_append_only
    BEFORE UPDATE OR DELETE ON payment_transition
    FOR EACH ROW EXECUTE FUNCTION nkap_forbid_mutation();

-- ------------------------------------------------------------------------------------
-- The ledger. Three invariants are made structural rather than checked:
--
--   * One currency per entry. The currency lives on the entry; a posting has no currency
--     column and inherits the entry's. A mixed-currency entry cannot be expressed.
--
--   * Zero-sum spans an entry's posting rows, so it cannot be a row CHECK. A deferred
--     constraint trigger verifies it at COMMIT: postings are inserted one at a time and
--     the entry is only required to balance when the transaction ends.
--
--   * Shape, once recorded, cannot change. Zero-sum alone does not stop a balanced pair
--     being appended to an entry recorded a week ago — the sum stays zero and nothing
--     forbids the extra postings. The entry declares its own posting count, and the
--     deferred trigger requires the actual count to equal it, not merely be at least two.
--
-- Both ledger tables are append-only.
-- ------------------------------------------------------------------------------------

CREATE TABLE ledger_entry (
    id            text        PRIMARY KEY,
    occurred_at   timestamptz NOT NULL,
    reference     text        NOT NULL,
    description   text        NOT NULL DEFAULT '',
    currency      text        NOT NULL,
    posting_count integer     NOT NULL CONSTRAINT ledger_entry_two_sided CHECK (posting_count >= 2)
);

CREATE INDEX ledger_entry_reference_idx ON ledger_entry (reference);

CREATE TABLE posting (
    id           bigint  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id     text    NOT NULL REFERENCES ledger_entry (id),
    seq          integer NOT NULL,
    account      text    NOT NULL,
    amount_minor bigint  NOT NULL,
    UNIQUE (entry_id, seq),
    CONSTRAINT posting_amount_nonzero CHECK (amount_minor <> 0)
);

CREATE INDEX posting_entry_idx ON posting (entry_id);
CREATE INDEX posting_account_idx ON posting (account);

CREATE TRIGGER ledger_entry_append_only
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION nkap_forbid_mutation();

CREATE TRIGGER posting_append_only
    BEFORE UPDATE OR DELETE ON posting
    FOR EACH ROW EXECUTE FUNCTION nkap_forbid_mutation();

CREATE FUNCTION nkap_ledger_entry_balances() RETURNS trigger
    LANGUAGE plpgsql AS $$
DECLARE
    checked_entry_id text;
    declared_count    integer;
    actual_count      integer;
    actual_sum        bigint;
BEGIN
    -- Deferred, and attached to both posting and ledger_entry, because either can be the
    -- last write of a transaction that leaves an entry wrong. A posting insert catches a
    -- short entry, an unbalanced one, or a balanced pair appended to an entry recorded
    -- earlier. A ledger_entry insert catches an entry that never receives a single posting
    -- at all — no posting insert ever fires for that one, so the entry needs its own check.
    IF TG_TABLE_NAME = 'posting' THEN
        checked_entry_id := NEW.entry_id;
    ELSE
        checked_entry_id := NEW.id;
    END IF;

    SELECT posting_count INTO declared_count FROM ledger_entry WHERE id = checked_entry_id;

    SELECT count(*), coalesce(sum(amount_minor), 0)
      INTO actual_count, actual_sum
      FROM posting
     WHERE entry_id = checked_entry_id;

    IF actual_count <> declared_count THEN
        RAISE EXCEPTION 'ledger entry "%" declares % posting(s) but has %: its shape cannot change after it is recorded',
            checked_entry_id, declared_count, actual_count
            USING ERRCODE = 'check_violation';
    END IF;

    IF actual_sum <> 0 THEN
        RAISE EXCEPTION 'ledger entry "%" does not balance: its postings sum to %, expected 0',
            checked_entry_id, actual_sum
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER posting_entry_balances
    AFTER INSERT ON posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION nkap_ledger_entry_balances();

CREATE CONSTRAINT TRIGGER ledger_entry_declared_balances
    AFTER INSERT ON ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION nkap_ledger_entry_balances();
