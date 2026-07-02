package com.datakite.ledger.exception;

public class InvalidTransactionStateException extends RuntimeException {
    public InvalidTransactionStateException(String message) {
        super(message);
    }
}
