package com.datakite.ledger.dto;

import com.datakite.ledger.model.Transaction;
import com.datakite.ledger.model.TransactionStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        UUID sourceAccountId,
        UUID destinationAccountId,
        BigDecimal amount,
        String currency,
        OffsetDateTime date,
        TransactionStatus status,
        String idempotencyKey
) {
    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getSourceAccountId(),
                transaction.getDestinationAccountId(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getDate(),
                transaction.getStatus(),
                transaction.getIdempotencyKey()
        );
    }
}
