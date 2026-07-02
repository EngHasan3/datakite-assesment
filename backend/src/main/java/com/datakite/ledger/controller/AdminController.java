package com.datakite.ledger.controller;

import com.datakite.ledger.dto.TransactionResponse;
import com.datakite.ledger.service.AdminReviewService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Lets an admin resolve a transfer that FraudDetectionAspect parked in
 * PENDING_REVIEW: release it (moves the funds) or reject it.
 */
@RestController
@RequestMapping("/api/v1/ledger/admin/transactions")
@RequiredArgsConstructor
public class AdminController {

    private final AdminReviewService adminReviewService;

    @PostMapping("/{id}/release")
    public TransactionResponse release(@PathVariable UUID id) {
        return adminReviewService.release(id);
    }

    @PostMapping("/{id}/reject")
    public TransactionResponse reject(@PathVariable UUID id) {
        return adminReviewService.reject(id);
    }
}
