package com.revolut.interview.transactions;

import com.revolut.interview.persistence.AbstractDAO;
import org.hibernate.Session;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;
import java.util.List;

@Singleton
public class TransactionDAO extends AbstractDAO<TransactionEntity> {

    @Inject
    TransactionDAO(Provider<Session> sessionProvider) {
        super(sessionProvider);
    }

    public boolean updateState(long id, TransactionState currentState, TransactionState newState) {
        return runInTransactionOrStartNewIfNotRunning(session -> {
            var query = session.createQuery("UPDATE transactions SET state = :newState WHERE state = :currentState AND id = :id");
            query.setParameter("id", id);
            query.setParameter("currentState", currentState.name());
            query.setParameter("newState", newState.name());

            return query.executeUpdate() > 0;
        });
    }

    @SuppressWarnings("unchecked")
    public List<TransactionEntity> findAllWithAccountId(long accountId) {
        return runInTransactionOrStartNewIfNotRunning(session -> {
            var query = session.createQuery("FROM transactions WHERE sender_id = :sender_id OR receiver_id = :receiver_id");
            query.setParameter("sender_id", accountId);
            query.setParameter("receiver_id", accountId);

            return query.list();
        });
    }
}
