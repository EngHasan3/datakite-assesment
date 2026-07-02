package com.datakite.ledger.service;

import com.datakite.ledger.dto.TransactionResponse;
import com.datakite.ledger.exception.InsufficientFundsException;
import com.datakite.ledger.exception.InvalidTransactionStateException;
import com.datakite.ledger.exception.TransactionNotFoundException;
import com.datakite.ledger.model.Account;
import com.datakite.ledger.model.Transaction;
import com.datakite.ledger.model.TransactionStatus;
import com.datakite.ledger.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Releases or rejects a transfer that FraudDetectionAspect parked in
 * PENDING_REVIEW. Releasing performs the same deadlock-safe locked
 * debit/credit that a normal transfer would have done had it not been
 * flagged.
 */
@Service
@RequiredArgsConstructor
public class AdminReviewService {

    private final TransactionRepository transactionRepository;
    private final AccountLockService accountLockService;

    @Transactional
    public TransactionResponse release(UUID transactionId) {
        Transaction transaction = requirePendingReview(transactionId);

        Map<UUID, Account> locked = accountLockService.lockAccounts(
                transaction.getSourceAccountId(), transaction.getDestinationAccountId());
        Account source = locked.get(transaction.getSourceAccountId());
        Account destination = locked.get(transaction.getDestinationAccountId());

        if (source.getBalance().compareTo(transaction.getAmount()) < 0) {
            transaction.setStatus(TransactionStatus.FAILED);
            transactionRepository.save(transaction);
            throw new InsufficientFundsException(source.getAccountNumber());
        }

        source.setBalance(source.getBalance().subtract(transaction.getAmount()));
        destination.setBalance(destination.getBalance().add(transaction.getAmount()));

        transaction.setStatus(TransactionStatus.COMPLETED);
        transactionRepository.save(transaction);
        return TransactionResponse.from(transaction);
    }

    @Transactional
    public TransactionResponse reject(UUID transactionId) {
        Transaction transaction = requirePendingReview(transactionId);
        transaction.setStatus(TransactionStatus.REJECTED);
        transactionRepository.save(transaction);
        return TransactionResponse.from(transaction);
    }

    private Transaction requirePendingReview(UUID transactionId) {
        Transaction transaction = transactionRepository.findById(transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        if (transaction.getStatus() != TransactionStatus.PENDING_REVIEW) {
            throw new InvalidTransactionStateException(
                    "Transaction " + transactionId + " is not pending review (status=" + transaction.getStatus() + ")");
        }
        return transaction;
    }
}
