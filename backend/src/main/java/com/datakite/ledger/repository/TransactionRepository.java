package com.datakite.ledger.repository;

import com.datakite.ledger.dto.StatusSummary;
import com.datakite.ledger.model.Transaction;
import com.datakite.ledger.model.TransactionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdempotencyKey(String idempotencyKey);

    Page<Transaction> findByStatus(TransactionStatus status, Pageable pageable);

    @Query("""
            select new com.datakite.ledger.dto.StatusSummary(t.status, count(t), coalesce(sum(t.amount), 0))
            from Transaction t
            group by t.status
            """)
    List<StatusSummary> summarizeByStatus();
}
