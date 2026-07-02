package com.datakite.ledger.dto;

import com.datakite.ledger.model.Account;

import java.math.BigDecimal;
import java.util.UUID;

public record AccountResponse(UUID id, String accountNumber, BigDecimal balance) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getAccountNumber(), account.getBalance());
    }
}
