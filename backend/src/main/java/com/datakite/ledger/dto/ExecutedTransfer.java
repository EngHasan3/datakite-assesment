package com.datakite.ledger.dto;

import org.springframework.http.HttpStatus;

public record ExecutedTransfer(TransactionResponse response, HttpStatus httpStatus) {
}
