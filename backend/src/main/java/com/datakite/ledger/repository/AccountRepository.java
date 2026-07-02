package com.datakite.ledger.repository;

import com.datakite.ledger.model.Account;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    /**
     * Id-only projection, deliberately never returning a full Account
     * entity. Resolving an account number to an entity here and then
     * separately locking it by id would load an unlocked, unmanaged-by-lock
     * Account into the persistence context first; Hibernate's identity map
     * then returns that SAME cached (pre-wait, stale) instance from the
     * later findByIdForUpdate call instead of refreshing it from the
     * FOR UPDATE result set. The DB row lock is still acquired correctly,
     * but the Java-side balance/version fields silently stay stale, and the
     * transfer computes on data captured before it ever waited for the
     * lock. Keeping account-number resolution id-only avoids ever creating
     * that first unlocked entity.
     */
    @Query("select a.id from Account a where a.accountNumber = :accountNumber")
    Optional<UUID> findIdByAccountNumber(@Param("accountNumber") String accountNumber);

    /**
     * Row-level SELECT ... FOR UPDATE. Callers always acquire locks on the
     * two accounts involved in a transfer in a fixed order (lowest id
     * first) before reading either balance, so concurrent transfers touching
     * the same account serialize here instead of racing on a dirty read.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") UUID id);
}
