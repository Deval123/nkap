-- Proposed adjustments and the decisions on them (ADR 0017, issue #266).
--
-- A human can finish what the reconciler cannot, in two steps by two different keys: one
-- proposes, another decides. A proposal is data until a decision approves it, and approving
-- is the only thing that writes to the ledger -- the server writes that entry, not the
-- proposer, and the entry's balance is still enforced by V1's deferred constraint trigger,
-- not by anything here.
--
-- Both tables are append-only under nkap_forbid_mutation(), the guard V1 defined for the
-- ledger and the payment history. What a decision says about a proposal is a new row, never
-- an UPDATE of the proposal, so the record of what was proposed cannot be rewritten after
-- the fact.
--
-- key_id on both tables is api_key.id: the separation of duties is built on key identity,
-- not on a permission (ADR 0017). Nothing is added to api_key.
--
-- The hand-written inverse is db/rollback/V13__adjustment_proposals.sql, exercised by
-- MigrationRollbackIT.

CREATE TABLE adjustment_proposal (
    id                uuid        PRIMARY KEY,
    payment_reference uuid        NOT NULL REFERENCES payment (reference),
    hypothesis        text        NOT NULL,
    postings          jsonb       NOT NULL,
    evidence          jsonb       NOT NULL,
    explanation       text        NOT NULL,
    key_id            uuid        NOT NULL REFERENCES api_key (id),
    created_at        timestamptz NOT NULL,
    -- A closed set, listed here as well as in Hypothesis: an unknown value is refused by the
    -- database too, so a row written around the application cannot carry one.
    CONSTRAINT adjustment_proposal_hypothesis_known CHECK (hypothesis IN (
        'unrecorded_fee', 'unattributed_movement', 'float_drift', 'duplicate_posting'))
);

CREATE INDEX adjustment_proposal_payment_idx ON adjustment_proposal (payment_reference);

CREATE TABLE adjustment_decision (
    id          uuid        PRIMARY KEY,
    proposal_id uuid        NOT NULL REFERENCES adjustment_proposal (id),
    approved    boolean     NOT NULL,
    key_id      uuid        NOT NULL REFERENCES api_key (id),
    decided_at  timestamptz NOT NULL,
    reason      text        NOT NULL DEFAULT '',
    -- One decision per proposal, enforced here and not by the calling code: two keys deciding
    -- at once cannot both win.
    CONSTRAINT adjustment_decision_one_per_proposal UNIQUE (proposal_id)
);

CREATE TRIGGER adjustment_proposal_append_only
    BEFORE UPDATE OR DELETE ON adjustment_proposal
    FOR EACH ROW EXECUTE FUNCTION nkap_forbid_mutation();

CREATE TRIGGER adjustment_decision_append_only
    BEFORE UPDATE OR DELETE ON adjustment_decision
    FOR EACH ROW EXECUTE FUNCTION nkap_forbid_mutation();
