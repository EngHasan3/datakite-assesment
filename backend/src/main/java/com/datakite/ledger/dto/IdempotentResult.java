package com.datakite.ledger.dto;

import org.springframework.http.HttpStatus;

public record IdempotentResult(TransactionResponse response, HttpStatus httpStatus, boolean replay) {
}
