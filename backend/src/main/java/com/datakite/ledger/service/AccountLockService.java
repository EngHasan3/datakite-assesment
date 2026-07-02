package com.datakite.ledger.service;

import com.datakite.ledger.exception.AccountNotFoundException;
import com.datakite.ledger.model.Account;
import com.datakite.ledger.repository.AccountRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Shared deadlock-safe account locking used by every code path that debits
 * one account and credits another (transfer execution, admin release).
 *
 * Locks are always acquired lowest-id-first, regardless of which id is the
 * "source" in the caller's context. Two concurrent transfers moving money in
 * opposite directions between the same pair of accounts would otherwise each
 * lock their own source first and deadlock against each other.
 *
 * Propagation.MANDATORY asserts the caller already opened a transaction -
 * PESSIMISTIC_WRITE has no effect outside one, and failing loudly here beats
 * silently running unlocked.
 */
@Service
@RequiredArgsConstructor
public class AccountLockService {

    private final AccountRepository accountRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, Account> lockAccounts(UUID idA, UUID idB) {
        UUID firstId = idA.compareTo(idB) <= 0 ? idA : idB;
        UUID secondId = firstId.equals(idA) ? idB : idA;

        Account first = lockById(firstId);
        Account second = lockById(secondId);
        return Map.of(first.getId(), first, second.getId(), second);
    }

    private Account lockById(UUID id) {
        return accountRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new AccountNotFoundException(id.toString()));
    }
}
