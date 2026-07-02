package com.datakite.ledger.service;

import com.datakite.ledger.dto.ExecutedTransfer;
import com.datakite.ledger.dto.IdempotentResult;
import com.datakite.ledger.dto.TransactionResponse;
import com.datakite.ledger.dto.TransferRequest;
import com.datakite.ledger.exception.IdempotencyConflictException;
import com.datakite.ledger.exception.IdempotencyInProgressException;
import com.datakite.ledger.model.IdempotencyRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Orchestrates the transfer endpoint end to end: claim the idempotency key,
 * run the transfer exactly once for the winner, replay the cached result for
 * everyone else.
 *
 * Deliberately NOT @Transactional - each step (claim, execute, complete) is
 * its own independent transaction (see IdempotencyService and
 * TransferExecutionService). Wrapping this method in one transaction would
 * pull the placeholder insert and the transfer's balance update into the
 * same unit of work, defeating the point of claiming the key before running
 * the business logic.
 */
@Service
@RequiredArgsConstructor
public class TransferService {

    private static final int MAX_WAIT_ATTEMPTS = 30;
    private static final long WAIT_INTERVAL_MILLIS = 100L;

    private final TransferExecutionService transferExecutionService;
    private final IdempotencyService idempotencyService;
    private final ObjectMapper objectMapper;

    public IdempotentResult transfer(TransferRequest request, String idempotencyKey) {
        String fingerprint = fingerprint(request);

        boolean claimed;
        try {
            idempotencyService.claim(idempotencyKey, fingerprint);
            claimed = true;
        } catch (DataIntegrityViolationException alreadyClaimed) {
            claimed = false;
        }

        if (claimed) {
            return executeAndRecord(request, idempotencyKey, fingerprint);
        }

        return awaitCachedResult(idempotencyKey, fingerprint);
    }

    private IdempotentResult executeAndRecord(TransferRequest request, String idempotencyKey, String fingerprint) {
        ExecutedTransfer executed = transferExecutionService.executeTransfer(request, idempotencyKey);
        idempotencyService.complete(idempotencyKey, executed.httpStatus().value(), writeJson(executed.response()));
        return new IdempotentResult(executed.response(), executed.httpStatus(), false);
    }

    private IdempotentResult awaitCachedResult(String idempotencyKey, String fingerprint) {
        for (int attempt = 0; attempt < MAX_WAIT_ATTEMPTS; attempt++) {
            Optional<IdempotencyRecord> completed = idempotencyService.findCompleted(idempotencyKey);
            if (completed.isPresent()) {
                return toReplayResult(completed.get(), fingerprint);
            }
            sleep();
        }
        throw new IdempotencyInProgressException(idempotencyKey);
    }

    private IdempotentResult toReplayResult(IdempotencyRecord record, String fingerprint) {
        if (!record.getRequestFingerprint().equals(fingerprint)) {
            throw new IdempotencyConflictException(record.getIdempotencyKey());
        }
        TransactionResponse response = readJson(record.getResponseBody());
        return new IdempotentResult(response, HttpStatus.valueOf(record.getHttpStatus()), true);
    }

    private void sleep() {
        try {
            Thread.sleep(WAIT_INTERVAL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdempotencyInProgressException("interrupted while waiting for the in-flight request");
        }
    }

    private String fingerprint(TransferRequest request) {
        String canonical = String.join("|",
                request.sourceAccountNumber(),
                request.destinationAccountNumber(),
                request.amount().stripTrailingZeros().toPlainString(),
                request.currency());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String writeJson(TransactionResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize transfer response", e);
        }
    }

    private TransactionResponse readJson(String json) {
        try {
            return objectMapper.readValue(json, TransactionResponse.class);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize cached transfer response", e);
        }
    }
}
