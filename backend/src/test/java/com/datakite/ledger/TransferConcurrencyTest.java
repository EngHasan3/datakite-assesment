package com.datakite.ledger;

import com.datakite.ledger.model.Account;
import com.datakite.ledger.repository.AccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the two claims in the assessment title: the ledger never
 * double-spends under concurrency, and a duplicated Idempotency-Key never
 * moves money twice.
 */
class TransferConcurrencyTest extends AbstractIntegrationTest {

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void concurrentTransfersFromSameAccountNeverOverdraw() throws InterruptedException {
        BigDecimal openingBalance = new BigDecimal("1000.0000");
        BigDecimal transferAmount = new BigDecimal("100.00");
        int concurrentRequests = 30; // far more than the 10 the balance can actually satisfy

        Account source = createAccount(openingBalance);
        Account destination = createAccount(BigDecimal.ZERO);

        List<ResponseEntity<Map>> results = fireConcurrently(concurrentRequests, i ->
                postTransfer(source.getAccountNumber(), destination.getAccountNumber(), transferAmount,
                        "concurrency-test-" + UUID.randomUUID()));

        long succeeded = results.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
        long insufficientFunds = results.stream().filter(r -> r.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY).count();

        int expectedSuccesses = openingBalance.divide(transferAmount).intValue(); // floor(1000/100) = 10
        assertThat(succeeded).isEqualTo(expectedSuccesses);
        assertThat(insufficientFunds).isEqualTo(concurrentRequests - expectedSuccesses);

        Account refreshedSource = accountRepository.findById(source.getId()).orElseThrow();
        Account refreshedDestination = accountRepository.findById(destination.getId()).orElseThrow();

        BigDecimal expectedSourceBalance = openingBalance.subtract(transferAmount.multiply(BigDecimal.valueOf(expectedSuccesses)));
        assertThat(refreshedSource.getBalance()).isEqualByComparingTo(expectedSourceBalance);
        assertThat(refreshedSource.getBalance()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(refreshedDestination.getBalance())
                .isEqualByComparingTo(transferAmount.multiply(BigDecimal.valueOf(expectedSuccesses)));
    }

    @Test
    void concurrentRequestsWithSameIdempotencyKeyMoveMoneyExactlyOnce() throws InterruptedException {
        BigDecimal openingBalance = new BigDecimal("1000.0000");
        BigDecimal transferAmount = new BigDecimal("250.00");
        int concurrentRequests = 15;
        String sharedIdempotencyKey = "shared-key-" + UUID.randomUUID();

        Account source = createAccount(openingBalance);
        Account destination = createAccount(BigDecimal.ZERO);

        List<ResponseEntity<Map>> results = fireConcurrently(concurrentRequests, i ->
                postTransfer(source.getAccountNumber(), destination.getAccountNumber(), transferAmount, sharedIdempotencyKey));

        assertThat(results).allMatch(r -> r.getStatusCode() == HttpStatus.CREATED);

        List<Object> transactionIds = results.stream()
                .map(r -> r.getBody().get("id"))
                .distinct()
                .toList();
        assertThat(transactionIds).hasSize(1);

        long replaysReported = results.stream()
                .filter(r -> "true".equals(r.getHeaders().getFirst("Idempotent-Replay")))
                .count();
        assertThat(replaysReported).isEqualTo(concurrentRequests - 1);

        Account refreshedSource = accountRepository.findById(source.getId()).orElseThrow();
        Account refreshedDestination = accountRepository.findById(destination.getId()).orElseThrow();
        assertThat(refreshedSource.getBalance()).isEqualByComparingTo(openingBalance.subtract(transferAmount));
        assertThat(refreshedDestination.getBalance()).isEqualByComparingTo(transferAmount);
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
        return restTemplate.exchange(baseUrl() + "/transfer", org.springframework.http.HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
    }

    /**
     * Uses a CountDownLatch as a start gate so every thread submits its
     * request within the same instant instead of trickling in - this is
     * what makes the test an actual concurrency proof rather than N
     * sequential calls that happen to run on different threads.
     */
    private <T> List<T> fireConcurrently(int count, java.util.function.IntFunction<T> task) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(count, 20));
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = IntStream.range(0, count)
                    .mapToObj(i -> executor.submit(() -> {
                        startGate.await();
                        return task.apply(i);
                    }))
                    .collect(Collectors.toList());
            startGate.countDown();
            return futures.stream().map(this::get).collect(Collectors.toList());
        } finally {
            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    private <T> T get(Future<T> future) {
        try {
            return future.get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
