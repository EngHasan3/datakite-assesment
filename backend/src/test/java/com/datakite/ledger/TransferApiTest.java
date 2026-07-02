package com.datakite.ledger;

import com.datakite.ledger.model.Account;
import com.datakite.ledger.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TransferApiTest extends AbstractIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void missingIdempotencyKeyHeaderIsRejected() {
        Account source = createAccount(new BigDecimal("1000.0000"));
        Account destination = createAccount(BigDecimal.ZERO);

        Map<String, Object> body = Map.of(
                "sourceAccountNumber", source.getAccountNumber(),
                "destinationAccountNumber", destination.getAccountNumber(),
                "amount", new BigDecimal("50.00"),
                "currency", "USD");

        ResponseEntity<Map> response = restTemplate.exchange(baseUrl() + "/transfer", HttpMethod.POST,
                new HttpEntity<>(body, new HttpHeaders()), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void replayingSameKeyAndPayloadReturnsCachedResponseWithoutMovingBalanceAgain() {
        Account source = createAccount(new BigDecimal("1000.0000"));
        Account destination = createAccount(BigDecimal.ZERO);
        String idempotencyKey = "replay-" + UUID.randomUUID();
        BigDecimal amount = new BigDecimal("50.00");

        ResponseEntity<Map> first = postTransfer(source.getAccountNumber(), destination.getAccountNumber(), amount, idempotencyKey);
        ResponseEntity<Map> second = postTransfer(source.getAccountNumber(), destination.getAccountNumber(), amount, idempotencyKey);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getBody().get("id")).isEqualTo(second.getBody().get("id"));
        assertThat(second.getHeaders().getFirst("Idempotent-Replay")).isEqualTo("true");

        Account refreshedSource = accountRepository.findById(source.getId()).orElseThrow();
        assertThat(refreshedSource.getBalance()).isEqualByComparingTo(new BigDecimal("1000.0000").subtract(amount));
    }

    @Test
    void reusingSameKeyWithDifferentPayloadIsRejected() {
        Account source = createAccount(new BigDecimal("1000.0000"));
        Account destination = createAccount(BigDecimal.ZERO);
        String idempotencyKey = "conflict-" + UUID.randomUUID();

        ResponseEntity<Map> first = postTransfer(source.getAccountNumber(), destination.getAccountNumber(),
                new BigDecimal("50.00"), idempotencyKey);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> second = postTransfer(source.getAccountNumber(), destination.getAccountNumber(),
                new BigDecimal("999.00"), idempotencyKey);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void transferOverFraudThresholdIsFlaggedForReviewAndDoesNotMoveMoney() {
        Account source = createAccount(new BigDecimal("50000.0000"));
        Account destination = createAccount(BigDecimal.ZERO);

        ResponseEntity<Map> response = postTransfer(source.getAccountNumber(), destination.getAccountNumber(),
                new BigDecimal("7500.00"), "fraud-" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody().get("status")).isEqualTo("PENDING_REVIEW");

        Account refreshedSource = accountRepository.findById(source.getId()).orElseThrow();
        Account refreshedDestination = accountRepository.findById(destination.getId()).orElseThrow();
        assertThat(refreshedSource.getBalance()).isEqualByComparingTo(new BigDecimal("50000.0000"));
        assertThat(refreshedDestination.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void adminCanReleaseAPendingReviewTransferAndFundsMove() {
        Account source = createAccount(new BigDecimal("50000.0000"));
        Account destination = createAccount(BigDecimal.ZERO);

        ResponseEntity<Map> flagged = postTransfer(source.getAccountNumber(), destination.getAccountNumber(),
                new BigDecimal("7500.00"), "fraud-release-" + UUID.randomUUID());
        String transactionId = (String) flagged.getBody().get("id");

        ResponseEntity<Map> released = restTemplate.postForEntity(
                baseUrl() + "/admin/transactions/" + transactionId + "/release", null, Map.class);

        assertThat(released.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(released.getBody().get("status")).isEqualTo("COMPLETED");

        Account refreshedSource = accountRepository.findById(source.getId()).orElseThrow();
        Account refreshedDestination = accountRepository.findById(destination.getId()).orElseThrow();
        assertThat(refreshedSource.getBalance()).isEqualByComparingTo(new BigDecimal("42500.0000"));
        assertThat(refreshedDestination.getBalance()).isEqualByComparingTo(new BigDecimal("7500.00"));
    }

    @Test
    void adminCanRejectAPendingReviewTransferAndBalancesStayUntouched() {
        Account source = createAccount(new BigDecimal("50000.0000"));
        Account destination = createAccount(BigDecimal.ZERO);

        ResponseEntity<Map> flagged = postTransfer(source.getAccountNumber(), destination.getAccountNumber(),
                new BigDecimal("7500.00"), "fraud-reject-" + UUID.randomUUID());
        String transactionId = (String) flagged.getBody().get("id");

        ResponseEntity<Map> rejected = restTemplate.postForEntity(
                baseUrl() + "/admin/transactions/" + transactionId + "/reject", null, Map.class);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rejected.getBody().get("status")).isEqualTo("REJECTED");

        Account refreshedSource = accountRepository.findById(source.getId()).orElseThrow();
        assertThat(refreshedSource.getBalance()).isEqualByComparingTo(new BigDecimal("50000.0000"));
    }

    private Account createAccount(BigDecimal balance) {
        Account account = Account.builder()
                .accountNumber("T" + UUID.randomUUID().toString().replace("-", ""))
                .balance(balance)
                .build();
        return accountRepository.save(account);
    }

    private ResponseEntity<Map> postTransfer(String sourceAccountNumber, String destinationAccountNumber,
                                              BigDecimal amount, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Idempotency-Key", idempotencyKey);
        Map<String, Object> body = Map.of(
                "sourceAccountNumber", sourceAccountNumber,
                "destinationAccountNumber", destinationAccountNumber,
                "amount", amount,
                "currency", "USD");
        return restTemplate.exchange(baseUrl() + "/transfer", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
    }
}
