package com.datakite.ledger.service;

import com.datakite.ledger.model.IdempotencyRecord;
import com.datakite.ledger.repository.IdempotencyRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Owns the idempotency_records table as three small, independent
 * transactions rather than one big one wrapping the transfer itself:
 *
 *  1. claim()    - insert a placeholder row. The unique key constraint is the
 *                  mutual-exclusion device: exactly one concurrent caller for
 *                  a given Idempotency-Key wins this insert.
 *  2. complete() - fill in the outcome once the transfer has run.
 *  3. findCompleted() - read-only lookup used both for a later replay and by
 *                  losers of claim() polling for the winner's result.
 *
 * Each method is REQUIRES_NEW so a unique-constraint violation in claim()
 * rolls back only that tiny insert attempt. If it shared a transaction with
 * the caller (or worse, with the transfer's own transaction), catching the
 * DataIntegrityViolationException would not un-poison that transaction -
 * Spring/Hibernate mark it rollback-only at the first flush failure, so any
 * later statement on the same transaction throws even though the exception
 * was caught in application code.
 */
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private final IdempotencyRecordRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void claim(String idempotencyKey, String requestFingerprint) {
        IdempotencyRecord placeholder = IdempotencyRecord.builder()
                .idempotencyKey(idempotencyKey)
                .requestFingerprint(requestFingerprint)
                .createdAt(OffsetDateTime.now())
                .build();
        repository.saveAndFlush(placeholder);
    }

    /**
     * Deliberately does not call repository.save(record): record is already
     * managed in this transaction's persistence context (loaded via
     * findById above), and IdempotencyRecord.isNew() is hardcoded true for
     * claim()'s benefit, so save() here would attempt another insert.
     * Hibernate's dirty checking flushes this mutation at commit regardless.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(String idempotencyKey, int httpStatus, String responseBody) {
        IdempotencyRecord record = repository.findById(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Idempotency claim vanished for key " + idempotencyKey));
        record.setHttpStatus(httpStatus);
        record.setResponseBody(responseBody);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public Optional<IdempotencyRecord> findCompleted(String idempotencyKey) {
        return repository.findById(idempotencyKey).filter(record -> record.getHttpStatus() != null);
    }
}
