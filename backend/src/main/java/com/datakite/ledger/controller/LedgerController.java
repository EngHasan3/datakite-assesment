package com.datakite.ledger.controller;

import com.datakite.ledger.dto.AccountResponse;
import com.datakite.ledger.dto.IdempotentResult;
import com.datakite.ledger.dto.PageResponse;
import com.datakite.ledger.dto.StatusSummary;
import com.datakite.ledger.dto.TransactionResponse;
import com.datakite.ledger.dto.TransferRequest;
import com.datakite.ledger.interceptor.IdempotencyKeyInterceptor;
import com.datakite.ledger.model.TransactionStatus;
import com.datakite.ledger.service.AccountService;
import com.datakite.ledger.service.TransactionQueryService;
import com.datakite.ledger.service.TransferService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/ledger")
@RequiredArgsConstructor
public class LedgerController {

    private static final String REPLAY_HEADER = "Idempotent-Replay";

    private final TransferService transferService;
    private final AccountService accountService;
    private final TransactionQueryService transactionQueryService;

    @PostMapping("/transfer")
    public ResponseEntity<TransactionResponse> transfer(
            @Valid @RequestBody TransferRequest request,
            @RequestHeader(IdempotencyKeyInterceptor.HEADER_NAME) String idempotencyKey) {
        IdempotentResult result = transferService.transfer(request, idempotencyKey);
        return ResponseEntity.status(result.httpStatus())
                .header(REPLAY_HEADER, String.valueOf(result.replay()))
                .body(result.response());
    }

    @GetMapping("/accounts")
    public List<AccountResponse> listAccounts() {
        return accountService.listAccounts();
    }

    @GetMapping("/transactions")
    public PageResponse<TransactionResponse> listTransactions(
            @RequestParam(required = false) TransactionStatus status,
            @PageableDefault(size = 20, sort = "date", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(transactionQueryService.listTransactions(status, pageable));
    }

    @GetMapping("/transactions/analytics/summary")
    public List<StatusSummary> transactionAnalytics() {
        return transactionQueryService.summarizeByStatus();
    }
}
