package com.datakite.ledger.exception;

/**
 * A concurrent request with the same Idempotency-Key is still executing and
 * has not recorded its outcome within the bounded wait window. The caller
 * should retry; this is not a business failure of the transfer itself.
 */
public class IdempotencyInProgressException extends RuntimeException {
    public IdempotencyInProgressException(String idempotencyKey) {
        super("A request with Idempotency-Key '" + idempotencyKey + "' is still being processed, retry shortly");
    }
}
