package com.datakite.ledger.dto;

import com.datakite.ledger.model.TransactionStatus;

import java.math.BigDecimal;

public record StatusSummary(TransactionStatus status, long count, BigDecimal totalAmount) {
}
