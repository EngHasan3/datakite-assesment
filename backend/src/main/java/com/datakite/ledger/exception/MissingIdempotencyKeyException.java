package com.datakite.ledger.exception;

public class MissingIdempotencyKeyException extends RuntimeException {
    public MissingIdempotencyKeyException() {
        super("The Idempotency-Key header is required for this request");
    }
}
