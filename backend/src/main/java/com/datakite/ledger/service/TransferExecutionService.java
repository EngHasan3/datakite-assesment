package com.datakite.ledger.service;

import com.datakite.ledger.dto.ExecutedTransfer;
import com.datakite.ledger.dto.TransactionResponse;
import com.datakite.ledger.dto.TransferRequest;
import com.datakite.ledger.exception.AccountNotFoundException;
import com.datakite.ledger.exception.InsufficientFundsException;
import com.datakite.ledger.model.Account;
import com.datakite.ledger.model.Transaction;
import com.datakite.ledger.model.TransactionStatus;
import com.datakite.ledger.repository.AccountRepository;
import com.datakite.ledger.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Executes the actual balance movement for a transfer.
 *
 * executeTransfer(..) is the join point FraudDetectionAspect advises: it is
 * only ever reached through this bean's Spring proxy (TransferService calls
 * it via an injected reference, never via self-invocation), so the aspect
 * reliably sees every call and can redirect amounts over the review
 * threshold to flagForReview(..) instead of letting the debit/credit happen.
 *
 * Resolves account numbers to ids via AccountRepository.findIdByAccountNumber
 * (never a full Account) and only loads Account entities through
 * AccountLockService's FOR UPDATE query - see that repository method's
 * Javadoc for why a preceding unlocked entity load would silently defeat
 * the lock.
 */
@Service
@RequiredArgsConstructor
public class TransferExecutionService {

    private final AccountRepository accountRepository;
    private final AccountLockService accountLockService;
    private final TransactionRepository transactionRepository;

    @Transactional
    public ExecutedTransfer executeTransfer(TransferRequest request, String idempotencyKey) {
        UUID sourceId = resolveAccountId(request.sourceAccountNumber());
        UUID destinationId = resolveAccountId(request.destinationAccountNumber());
        requireDifferentAccounts(sourceId, destinationId);

        Map<UUID, Account> locked = accountLockService.lockAccounts(sourceId, destinationId);
        Account lockedSource = locked.get(sourceId);
        Account lockedDestination = locked.get(destinationId);

        if (lockedSource.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientFundsException(request.sourceAccountNumber());
        }

        lockedSource.setBalance(lockedSource.getBalance().subtract(request.amount()));
        lockedDestination.setBalance(lockedDestination.getBalance().add(request.amount()));

        Transaction transaction = Transaction.builder()
                .sourceAccountId(sourceId)
                .destinationAccountId(destinationId)
                .amount(request.amount())
                .currency(request.currency())
                .date(OffsetDateTime.now())
                .status(TransactionStatus.COMPLETED)
                .idempotencyKey(idempotencyKey)
                .build();
        transactionRepository.save(transaction);

        return new ExecutedTransfer(TransactionResponse.from(transaction), HttpStatus.CREATED);
    }

    /**
     * Records the transfer as PENDING_REVIEW without touching either
     * balance. Called by FraudDetectionAspect instead of proceeding to
     * executeTransfer when the amount exceeds the fraud threshold.
     */
    @Transactional
    public ExecutedTransfer flagForReview(TransferRequest request, String idempotencyKey) {
        UUID sourceId = resolveAccountId(request.sourceAccountNumber());
        UUID destinationId = resolveAccountId(request.destinationAccountNumber());
        requireDifferentAccounts(sourceId, destinationId);

        Transaction transaction = Transaction.builder()
                .sourceAccountId(sourceId)
                .destinationAccountId(destinationId)
                .amount(request.amount())
                .currency(request.currency())
                .date(OffsetDateTime.now())
                .status(TransactionStatus.PENDING_REVIEW)
                .idempotencyKey(idempotencyKey)
                .build();
        transactionRepository.save(transaction);

        return new ExecutedTransfer(TransactionResponse.from(transaction), HttpStatus.ACCEPTED);
    }

    private UUID resolveAccountId(String accountNumber) {
        return accountRepository.findIdByAccountNumber(accountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountNumber));
    }

    private void requireDifferentAccounts(UUID sourceId, UUID destinationId) {
        if (sourceId.equals(destinationId)) {
            throw new IllegalArgumentException("sourceAccountNumber and destinationAccountNumber must differ");
        }
    }

}
