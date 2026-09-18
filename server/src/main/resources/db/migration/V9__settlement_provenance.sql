-- Provenance: which endpoint the gateway actually submitted a payment to (issue #122).
--
-- Two payments to the same MSISDN, the same provider_id ("mtn-cm"), one settled by the
-- simulator and one by the real MTN sandbox, were byte-identical in this table's provenance.
-- provider_id is derived from the installation's country (#82's routing), so it is the same
-- whether that country's installation points at MTN or at a stand-in for it; the only other
-- tell, providerTransactionId being blank, is also what a real operator returns for a
-- payment that failed -- a signal indistinguishable from a legitimate empty value is not one.
--
-- provider_base_url records the installation's own base URL at the moment this payment is
-- created. Self-describing (http://simulator:8081 needs no interpretation) and, unlike a
-- deployment-level label such as nkap.environment: production, it cannot lie about itself.
-- Set once, from configuration, never from a caller (the same reasoning as #116's callback
-- URL), and never again: a trigger on this column alone refuses any change to it once a row
-- exists. payment itself is not append-only -- state and refunded_minor both move -- so
-- nkap_forbid_mutation() (V1) cannot be reused as-is; this guards one column instead of the
-- whole row, the same discipline scaled down.
--
-- BEFORE UPDATE OF provider_base_url, not a bare BEFORE UPDATE: this column is never in any
-- application UPDATE's SET list today (PostgresPaymentRepository's UPSERT_PAYMENT deliberately
-- leaves it out, the same as refunded_minor), so a bare trigger would run on every payment
-- write -- every transition, every refund reservation -- to check a column that statement
-- never touched. The OF form fires only when a statement actually lists this column, which is
-- exactly the one case worth checking, and still catches a future SET list that adds it back:
-- the column being merely listed is what fires the trigger, whether or not the listed value
-- happens to equal what is already there.
--
-- Every row that predates this migration gets NULL, and nothing here backfills one. The
-- database this was found on already holds simulator payments and real-operator payments
-- side by side with nothing to tell them apart after the fact. Manufacturing a plausible
-- value for either kind now -- the current configuration's base URL, or a bare "production"
-- -- would be writing the exact false statement this issue exists to remove, and it would be
-- undetectable afterwards. NULL says "this payment predates provenance tracking," which is
-- true, rather than a guess dressed up as an answer.
--
-- Not added to ledger_entry. That table is append-only and its discipline is accounting --
-- account, amount, currency, direction -- and provenance is metadata; widening it to carry
-- provenance would weaken the thing that makes it trustworthy. ledger_entry.reference already
-- names the payment behind an entry, so an auditor follows that reference to
-- payment.provider_base_url instead of finding a second, parallel copy of it.
--
-- Not returned by GET /payments/{reference}: it is information about this deployment's own
-- operator connection, not about the payment, the same reasoning that keeps the management
-- port off the public API (application.yml's own comment on management.server.port).
--
-- The hand-written inverse of this migration is db/rollback/V9__settlement_provenance.sql,
-- exercised by MigrationRollbackIT.

ALTER TABLE payment ADD COLUMN provider_base_url text;

CREATE FUNCTION nkap_payment_provider_base_url_is_final() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.provider_base_url IS DISTINCT FROM OLD.provider_base_url THEN
        RAISE EXCEPTION 'payment.provider_base_url cannot change once recorded (% -> %)',
            OLD.provider_base_url, NEW.provider_base_url
            USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payment_provider_base_url_is_final
    BEFORE UPDATE OF provider_base_url ON payment
    FOR EACH ROW EXECUTE FUNCTION nkap_payment_provider_base_url_is_final();
