package com.datakite.ledger.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.domain.Persistable;

import java.time.OffsetDateTime;

/**
 * Caches the outcome of a request keyed by its Idempotency-Key header so a
 * retried request can be answered from this row instead of re-running
 * TransferService and touching balances a second time.
 *
 * requestFingerprint guards against key reuse with a different payload: a
 * replay must hash-match, otherwise it is rejected as a conflicting reuse of
 * the same key rather than served from cache.
 *
 * httpStatus/responseBody are null while the row is a "claim" placeholder
 * (the owning request is still executing) and are filled in once that
 * request finishes; a concurrent duplicate polls until they are set instead
 * of racing the original into TransferExecutionService.
 *
 * Implements Persistable so repository.save() always issues a real INSERT.
 * idempotencyKey is a manually-assigned (non-generated) id, and Spring Data
 * JPA's default isNew() check for such ids falls back to entityManager.merge()
 * - an upsert that would silently overwrite an existing completed record
 * with a blank placeholder instead of throwing a constraint violation,
 * defeating the unique-key-as-mutex trick IdempotencyService.claim() relies
 * on. Forcing isNew()=true makes save() always persist(), which genuinely
 * fails on a duplicate key.
 */
@Entity
@Table(name = "idempotency_records")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class IdempotencyRecord implements Persistable<String> {

    @Id
    @EqualsAndHashCode.Include
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "http_status")
    private Integer httpStatus;

    @Column(name = "response_body", columnDefinition = "text")
    private String responseBody;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Override
    public String getId() {
        return idempotencyKey;
    }

    /**
     * Always "new": the only place that calls save() is claim(), which must
     * always attempt a genuine insert. complete() mutates an already-managed
     * entity and relies on dirty-checking to flush the update, so it never
     * needs isNew()=false semantics.
     */
    @Override
    public boolean isNew() {
        return true;
    }
}
