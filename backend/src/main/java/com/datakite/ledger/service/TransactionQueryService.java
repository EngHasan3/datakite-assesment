package com.datakite.ledger.service;

import com.datakite.ledger.dto.StatusSummary;
import com.datakite.ledger.dto.TransactionResponse;
import com.datakite.ledger.model.Transaction;
import com.datakite.ledger.model.TransactionStatus;
import com.datakite.ledger.repository.TransactionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class TransactionQueryService {

    private final TransactionRepository transactionRepository;

    @Transactional(readOnly = true)
    public Page<TransactionResponse> listTransactions(TransactionStatus status, Pageable pageable) {
        Page<Transaction> page = status == null
                ? transactionRepository.findAll(pageable)
                : transactionRepository.findByStatus(status, pageable);
        return page.map(TransactionResponse::from);
    }

    @Transactional(readOnly = true)
    public List<StatusSummary> summarizeByStatus() {
        return transactionRepository.summarizeByStatus();
    }
}
