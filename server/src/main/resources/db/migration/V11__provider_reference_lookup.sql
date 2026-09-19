-- A lookup PaymentRepository needs to attribute an inbound callback that names only the
-- operator's own reference, never Nkap's (ADR 0011 SS2, issue #149): CallbackController
-- resolves provider_reference back to reference, the mirror of the lookup query() already
-- does in the other direction (issue #96).
--
-- The column itself is not new -- provider_reference has sat on payment, defaulted to '',
-- since V1__initial_schema.sql. What is new is a caller searching it by value instead of
-- only ever reading it off a row already found by reference. provider_reference stays '' for
-- every payment MTN has ever submitted (requesttopay's 202 carries no body) and for every
-- payment that predates whichever operator does populate it, so an unqualified index would
-- cover most of the table for a value nothing ever searches on -- the partial index excludes
-- it. Scoped by provider first: a provider reference is only meaningful within one operator's
-- own namespace, and provider is what the callback endpoint already knows, from the URL path,
-- before it has anything else to look up with.
--
-- The hand-written inverse of this migration is db/rollback/V11__provider_reference_lookup.sql,
-- exercised by MigrationRollbackIT.

CREATE INDEX payment_provider_reference_idx ON payment (provider, provider_reference)
    WHERE provider_reference <> '';
