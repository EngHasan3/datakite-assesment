package com.datakite.ledger.exception;

/**
 * Thrown when a request reuses an Idempotency-Key that is already on record
 * for a different request payload. A genuine replay must hash-match the
 * original request; otherwise it is key reuse, not a retry, and must not be
 * served from cache.
 */
public class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String idempotencyKey) {
        super("Idempotency-Key '" + idempotencyKey + "' was already used with a different request payload");
    }
}
