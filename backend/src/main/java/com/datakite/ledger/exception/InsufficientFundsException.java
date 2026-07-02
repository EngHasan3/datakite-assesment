package com.datakite.ledger.exception;

public class InsufficientFundsException extends RuntimeException {
    public InsufficientFundsException(String accountNumber) {
        super("Account " + accountNumber + " has insufficient funds for this transfer");
    }
}
