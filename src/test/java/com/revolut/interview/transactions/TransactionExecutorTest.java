package com.revolut.interview.transactions;

import com.revolut.interview.account.AccountEntity;
import com.revolut.interview.account.AccountsDAO;
import com.revolut.interview.notification.NotificationService;
import com.revolut.interview.notification.TransactionNotification;
import org.hibernate.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.inject.Provider;
import javax.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.util.Optional;

import static com.revolut.interview.transactions.TransactionState.FAILED;
import static com.revolut.interview.transactions.TransactionState.IN_PROGRESS;
import static com.revolut.interview.transactions.TransactionState.PENDING;
import static com.revolut.interview.transactions.TransactionState.SUCCEEDED;
import static java.math.BigDecimal.TEN;
import static javax.persistence.LockModeType.WRITE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionExecutorTest {

    private static final Transaction VALID_TRANSACTION = new Transaction(1L, 1L, 2L, TEN, PENDING);
    private static final BigDecimal BALANCE = BigDecimal.valueOf(100);

    @Mock
    private Session session;
    @Mock
    private org.hibernate.Transaction dbTransaction;

    @Mock
    private Provider<Session> sessionProvider;

    @Mock
    private AccountsDAO accountsDAO;
    @Mock
    private TransactionDAO transactionDAO;
    @Mock
    private NotificationService notificationService;
    @Mock
    private BackoffStrategy backoffStrategy;

    private AccountEntity receiver, sender;

    private TransactionExecutor transactionExecutor;

    @BeforeEach
    void setUp() {
        this.transactionExecutor = new TransactionExecutor(1,
                Runnable::run,
                sessionProvider,
                accountsDAO,
                transactionDAO,
                notificationService,
                backoffStrategy
        );

        setUpAccountsAndTransactionDAO();
        setUpSessions();
    }

    @Test
    void transactionShouldNotExecuteIfStateIsNotPending() {
        when(transactionDAO.updateState(1L, PENDING, IN_PROGRESS))
                .thenReturn(false);

        transactionExecutor.execute(VALID_TRANSACTION);

        verify(transactionDAO, never()).update(any(TransactionEntity.class));
        verifyNoMoreInteractions(accountsDAO);
    }

    @Test
    void shouldThrowIllegalArgumentExceptionWhenTransactionDoesNotExist() {
        var invalidTransaction = new Transaction(2L, 1L, 2L, BigDecimal.ONE, PENDING);

        assertThrows(IllegalArgumentException.class, () -> transactionExecutor.execute(invalidTransaction));
    }

    @Test
    void transactionShouldFailIfBalanceIsInsufficient() {
        when(transactionDAO.findById(1L)).thenReturn(Optional.of(new TransactionEntity(sender, receiver, BALANCE.add(TEN), PENDING)));

        transactionExecutor.execute(VALID_TRANSACTION);

        verifyTransactionEntityState(FAILED);

        var notification = new TransactionNotification(sender.getId(), receiver.getId(), false, BALANCE.add(TEN));
        verify(notificationService).sendNotification(notification);
    }

    @Test
    void failureNotificationShouldBeSentOnFailureWhenBalanceIsInsufficient() {
        when(transactionDAO.findById(1L)).thenReturn(Optional.of(new TransactionEntity(sender, receiver, BALANCE.add(TEN), PENDING)));

        transactionExecutor.execute(VALID_TRANSACTION);

        var notification = new TransactionNotification(sender.getId(), receiver.getId(), false, BALANCE.add(TEN));
        verify(notificationService).sendNotification(notification);
    }

    @Test
    void databaseTransactionShouldBeCommittedWhenTransferSucceeds() {
        transactionExecutor.execute(VALID_TRANSACTION);

        verify(session).beginTransaction();
        verify(dbTransaction).commit();

        verifyTransactionEntityState(SUCCEEDED);
    }

    @Test
    void notificationShouldBeSentWhenTransactionIsSuccessful() {
        transactionExecutor.execute(VALID_TRANSACTION);

        var notification = new TransactionNotification(sender.getId(), receiver.getId(), true, VALID_TRANSACTION.getAmountToTransfer());
        verify(notificationService).sendNotification(notification);
    }

    @Test
    void senderAccountShouldBeUpdatedWithExpectedParameters() {
        transactionExecutor.execute(VALID_TRANSACTION);

        var accountEntityCaptor = ArgumentCaptor.forClass(AccountEntity.class);
        verify(accountsDAO, times(2)).update(accountEntityCaptor.capture());

        var updatedEntities = accountEntityCaptor.getAllValues();
        var senderAccountEntity = updatedEntities.stream()
                .filter(e -> e.getId().equals(VALID_TRANSACTION.getSenderId()))
                .findFirst()
                .orElseThrow();

        assertEquals(BALANCE.subtract(VALID_TRANSACTION.getAmountToTransfer()), senderAccountEntity.getBalance());
    }

    @Test
    void receiverAccountShouldBeUpdatedWithExpectedParameters() {
        transactionExecutor.execute(VALID_TRANSACTION);

        var accountEntityCaptor = ArgumentCaptor.forClass(AccountEntity.class);
        verify(accountsDAO, times(2)).update(accountEntityCaptor.capture());

        var updatedEntities = accountEntityCaptor.getAllValues();
        var receiverAccountEntity = updatedEntities.stream()
                .filter(e -> e.getId().equals(VALID_TRANSACTION.getReceiverId()))
                .findFirst()
                .orElseThrow();

        assertEquals(BALANCE.add(VALID_TRANSACTION.getAmountToTransfer()).compareTo(receiverAccountEntity.getBalance()), 0);
    }

    @Test
    void transactionShouldBeRolledBackIfAOptimisticLockExceptionIsThrownWhileUpdatingSenderAccount() {
        simulateUpdateFailureForAccount(sender, OptimisticLockException.class);

        transactionExecutor.execute(VALID_TRANSACTION);

        var notification = new TransactionNotification(sender.getId(), receiver.getId(), false, VALID_TRANSACTION.getAmountToTransfer());
        verify(notificationService).sendNotification(notification);
    }

    @Test
    void notificationShouldBeSentWhenTransferFailsDueToOptimisticLockException() {
        simulateUpdateFailureForAccount(sender, OptimisticLockException.class);

        transactionExecutor.execute(VALID_TRANSACTION);

        verify(dbTransaction).rollback();
        verifyTransactionEntityState(FAILED);
    }

    @Test
    void transactionShouldBeRolledBackIfAOptimisticLockExceptionIsThrownWhileUpdatingReceiverAccount() {
        simulateUpdateFailureForAccount(receiver, OptimisticLockException.class);

        transactionExecutor.execute(VALID_TRANSACTION);

        verify(dbTransaction).rollback();
        verifyTransactionEntityState(FAILED);
    }

    @Test
    void transactionShouldBeRolledBackIfAnyExceptionIsThrownWhileUpdatingSenderAccount() {
        simulateUpdateFailureForAccount(receiver, RuntimeException.class);

        transactionExecutor.execute(VALID_TRANSACTION);

        verify(dbTransaction).rollback();
        verifyTransactionEntityState(FAILED);
    }

    @Test
    void transactionShouldBeRolledBackIfAnyExceptionIsThrownWhileUpdatingReceiverAccount() {
        simulateUpdateFailureForAccount(receiver, RuntimeException.class);

        transactionExecutor.execute(VALID_TRANSACTION);

        verify(dbTransaction).rollback();
        verifyTransactionEntityState(FAILED);
    }

    @Test
    void transactionShouldBeRolledBackAndExceptionShouldBeBubbledIfAnyExceptionIsThrownWhileUpdatingTransactionState() {
        doThrow(RuntimeException.class).when(transactionDAO).update(any(TransactionEntity.class));

        //because unable to update the transaction state bubbles up the exception. This is catastrophic
        assertThrows(RuntimeException.class, () -> transactionExecutor.execute(VALID_TRANSACTION));

        verify(dbTransaction).rollback();
        verify(transactionDAO, times(2)).update(any(TransactionEntity.class));

        verify(notificationService, never()).sendNotification(any(TransactionNotification.class));
    }

    @Test
    void multipleAttemptsShouldBeMadeToExecuteTransferWhenOptimisticLockExceptionIsThrown() {
        this.transactionExecutor = new TransactionExecutor(2,
                Runnable::run,
                sessionProvider,
                accountsDAO,
                transactionDAO,
                notificationService, backoffStrategy);

        doThrow(OptimisticLockException.class)
                .doNothing()
                .when(accountsDAO)
                .update(any(AccountEntity.class));

        transactionExecutor.execute(VALID_TRANSACTION);

        verify(dbTransaction).rollback();
        verify(dbTransaction).commit();
        verify(accountsDAO, times(4)).findById(anyLong(), eq(WRITE));
        verify(accountsDAO, times(2)).update(sender);
        verify(accountsDAO).update(receiver);

        verify(notificationService).sendNotification(any(TransactionNotification.class));

        verify(backoffStrategy).backOff(2);
    }

    private void simulateUpdateFailureForAccount(AccountEntity accountEntity, Class<? extends Throwable> exceptionType) {
        doThrow(exceptionType)
                .when(accountsDAO)
                .update(argThat(argument -> argument.getId().equals(accountEntity.getId())));

        lenient()
                .doNothing()
                .when(accountsDAO)
                .update(argThat(argument -> !argument.getId().equals(accountEntity.getId())));
    }

    private void setUpSessions() {
        lenient()
                .when(sessionProvider.get()).thenReturn(session);

        lenient()
                .when(session.beginTransaction()).thenReturn(dbTransaction);
    }

    private void setUpAccountsAndTransactionDAO() {
        sender = new AccountEntity();
        sender.setId(VALID_TRANSACTION.getSenderId());
        sender.setBalance(BALANCE);

        receiver = new AccountEntity();
        receiver.setId(VALID_TRANSACTION.getReceiverId());
        receiver.setBalance(BALANCE);

        lenient()
                .when(accountsDAO.findById(VALID_TRANSACTION.getSenderId(), WRITE))
                .thenReturn(Optional.of(sender));

        lenient()
                .when(accountsDAO.findById(VALID_TRANSACTION.getReceiverId(), WRITE))
                .thenReturn(Optional.of(receiver));

        lenient()
                .when(transactionDAO.findById(1L))
                .thenReturn(
                        Optional.of(
                                new TransactionEntity(
                                        sender,
                                        receiver,
                                        VALID_TRANSACTION.getAmountToTransfer(),
                                        TransactionState.PENDING
                                )
                        )
                );

        lenient()
                .when(transactionDAO.updateState(1L, PENDING, IN_PROGRESS))
                .thenReturn(true);
    }

    private void verifyTransactionEntityState(TransactionState expected) {
        var transactionEntityCaptor = ArgumentCaptor.forClass(TransactionEntity.class);
        verify(transactionDAO).update(transactionEntityCaptor.capture());

        assertEquals(expected, transactionEntityCaptor.getValue().getTransactionState());
    }
}
