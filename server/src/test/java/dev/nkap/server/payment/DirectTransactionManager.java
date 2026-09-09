package dev.nkap.server.payment;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Runs each transaction callback directly, with no real transaction.
 *
 * <p>For the service unit tests, which are single-threaded and use the in-memory store
 * doubles: there is no database to open a transaction against and no concurrency to
 * serialise. The real transactional behaviour — one transaction for the ledger write and
 * the payment state, the {@code FOR UPDATE} row lock, rollback on failure — is covered by
 * the {@code *IT} tests against a real PostgreSQL.
 */
final class DirectTransactionManager implements PlatformTransactionManager {

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
        // nothing to commit
    }

    @Override
    public void rollback(TransactionStatus status) {
        // nothing to roll back
    }
}
