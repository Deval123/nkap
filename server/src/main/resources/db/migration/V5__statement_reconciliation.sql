-- Statement reconciliation: an imported operator statement, its lines, and the report the
-- import produced. Roadmap section 4; the fee entries ADR 0006 has been waiting for, and
-- the first thing that ever posts to a suspense account.
--
-- All three tables are append-only. An import is written once with its final per-outcome
-- counts; a line is the operator's evidence, recorded exactly as parsed; a finding is one
-- decision the reconciliation made. A mistake is corrected by a new import, never by an
-- UPDATE -- the same rule as the ledger and the transition history, and enforced by the
-- same nkap_forbid_mutation() trigger from V1.
--
-- Re-import safety is structural, not here: StatementReconciliation derives each fee and
-- suspense ledger entry's id from the statement line's identity, so a second import is
-- refused by ledger_entry's primary key. This migration adds no dedup of its own.
--
-- Hand-written inverse: db/rollback/V5__statement_reconciliation.sql, exercised by
-- MigrationRollbackIT.

CREATE TABLE statement_import (
    id                 uuid        PRIMARY KEY,
    provider           text        NOT NULL,
    source_name        text        NOT NULL,
    imported_at        timestamptz NOT NULL,
    line_count             integer NOT NULL,
    fee_posted_count       integer NOT NULL DEFAULT 0,
    fee_already_count      integer NOT NULL DEFAULT 0,
    matched_count          integer NOT NULL DEFAULT 0,
    suspense_count         integer NOT NULL DEFAULT 0,
    suspense_already_count integer NOT NULL DEFAULT 0,
    missing_count          integer NOT NULL DEFAULT 0,
    mismatch_count         integer NOT NULL DEFAULT 0,
    CONSTRAINT statement_import_line_count_nonneg CHECK (line_count >= 0)
);

CREATE INDEX statement_import_provider_idx ON statement_import (provider, imported_at);

CREATE TABLE statement_line (
    import_id               uuid        NOT NULL REFERENCES statement_import (id),
    seq                     integer     NOT NULL,
    operator_transaction_id text        NOT NULL,
    amount_minor            bigint      NOT NULL,
    fee_minor               bigint      NOT NULL,
    currency                text        NOT NULL,
    occurred_at             timestamptz NOT NULL,
    status                  text        NOT NULL,
    PRIMARY KEY (import_id, seq),
    CONSTRAINT statement_line_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT statement_line_fee_nonneg CHECK (fee_minor >= 0)
);

CREATE TABLE statement_finding (
    import_id               uuid        NOT NULL REFERENCES statement_import (id),
    seq                     integer     NOT NULL,
    kind                    text        NOT NULL,
    operator_transaction_id text,
    payment_reference       uuid,
    detail                  text        NOT NULL DEFAULT '',
    PRIMARY KEY (import_id, seq)
);

CREATE TRIGGER statement_import_append_only
    BEFORE UPDATE OR DELETE ON statement_import
    FOR EACH ROW EXECUTE FUNCTION nkap_forbid_mutation();

CREATE TRIGGER statement_line_append_only
    BEFORE UPDATE OR DELETE ON statement_line
    FOR EACH ROW EXECUTE FUNCTION nkap_forbid_mutation();

CREATE TRIGGER statement_finding_append_only
    BEFORE UPDATE OR DELETE ON statement_finding
    FOR EACH ROW EXECUTE FUNCTION nkap_forbid_mutation();
