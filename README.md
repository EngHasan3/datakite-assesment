# datakite-assesment

A full-stack prototype of a high-concurrency, idempotent financial transfer ledger. Built for the
DataKite engineering assessment: prove a transfer ledger never double-spends under concurrent load,
never re-executes a duplicated request, and pauses high-value transfers for review.

- **Backend:** Java 17, Spring Boot 3.5, PostgreSQL, Spring Data JPA, Liquibase
- **Frontend:** Next.js (App Router), React 19, shadcn/ui, Tailwind CSS, Recharts

See [`AI_LOG.md`](AI_LOG.md) for the AI-assisted development log (tooling, prompts, and the bugs it
introduced that had to be caught and fixed).

## How the core requirements are implemented

| Requirement | Where | How |
|---|---|---|
| `POST /api/v1/ledger/transfer` | `LedgerController` | Accepts `sourceAccountNumber`, `destinationAccountNumber`, `amount`, `currency`. |
| Mandatory `Idempotency-Key` header | `IdempotencyKeyInterceptor` | Rejects the request with 400 if the header is missing. Explicitly lets `OPTIONS` (CORS preflight) through — see AI_LOG. |
| Idempotent replay | `TransferService` + `IdempotencyService` | A unique-key "claim" row is inserted *before* the transfer runs. A duplicate request never gets past the claim and instead polls for, then replays, the first request's cached response — see [Idempotency design](#idempotency-design) below. |
| Concurrency guard / no double-spend | `AccountLockService`, `TransferExecutionService` | Pessimistic row locking (`SELECT ... FOR UPDATE`) on both accounts, acquired in a fixed (lowest-id-first) order to avoid deadlocks between opposite-direction transfers. `Account.version` (`@Version`) is kept as a defense-in-depth optimistic-lock backstop. |
| Fraud review over $5,000 | `FraudDetectionAspect` (Spring AOP) | `@Around` advice on `TransferExecutionService.executeTransfer` redirects any transfer whose amount exceeds the threshold to `flagForReview`, which records the transfer as `PENDING_REVIEW` without touching either balance. |
| Admin release/reject | `AdminController`, `AdminReviewService` | `POST /admin/transactions/{id}/release` performs the locked debit/credit and marks `COMPLETED`; `/reject` marks `REJECTED` without moving funds. |

### Idempotency design

`idempotency_records` has `idempotency_key` as its primary key. `IdempotencyService.claim()` inserts
a placeholder row for the key *before* the transfer executes; the unique constraint on that column is
the mutual-exclusion device — for concurrent requests sharing a key, exactly one `INSERT` wins.

- **Winner:** proceeds to execute the transfer, then fills in the placeholder with the real response
  (`IdempotencyService.complete()`).
- **Losers:** catch the constraint violation and poll (bounded, ~3s) for the winner's row to complete,
  then return its cached response — verifying the request fingerprint (a hash of the payload) matches
  first, so key reuse with a *different* payload is rejected as a conflict (409) rather than served
  from cache.

Each of `claim()` / `complete()` / the transfer itself runs in its **own** transaction
(`Propagation.REQUIRES_NEW`), so a constraint violation in `claim()` never poisons the transaction that
runs the transfer, and a successful transfer is never rolled back by a later idempotency-bookkeeping
failure.

### Proving it never double-spends

`backend/src/test/java/com/datakite/ledger/TransferConcurrencyTest.java` boots the full application
against a real PostgreSQL container (Testcontainers) and drives it over real HTTP with genuinely
concurrent requests (a `CountDownLatch` start gate releases every thread at once):

- **`concurrentTransfersFromSameAccountNeverOverdraw`** — fires 30 concurrent $100 transfers from an
  account with a $1,000 balance. Asserts *exactly* `floor(1000/100) = 10` succeed, the rest fail with
  insufficient funds, and the final balance is exactly `1000 - 10*100 = 0` — never negative, never
  double-counted.
- **`concurrentRequestsWithSameIdempotencyKeyMoveMoneyExactlyOnce`** — fires 15 concurrent requests
  sharing one `Idempotency-Key`. Asserts they all resolve to the *same* transaction id and the balance
  moves exactly once.

## Quickstart (Docker Compose)

Requires Docker and Docker Compose. This builds and runs Postgres, the backend (Liquibase migrations
run automatically on boot), and the frontend:

```bash
docker compose up --build
```

- Frontend dashboard: http://localhost:3000
- Backend API: http://localhost:8080/api/v1/ledger
- Postgres: localhost:5432 (`ledger` / `ledger` / db `ledger`)

If any of ports 3000/8080/5432 are already in use on your machine, edit the `ports:` mappings in
`docker-compose.yml` before starting.

Five demo accounts (`ACC-1001`..`ACC-1005`) are seeded by a Liquibase changeset on first boot.

## Local development (without Docker)

### Backend

```bash
cd backend
docker run -d --name ledger-postgres -e POSTGRES_DB=ledger -e POSTGRES_USER=ledger \
  -e POSTGRES_PASSWORD=ledger -p 5432:5432 postgres:16-alpine
./mvnw spring-boot:run
```

Migrations (Liquibase, `src/main/resources/db/changelog/`) run automatically against the configured
datasource on startup — no separate migrate step. Config lives in
`src/main/resources/application.properties`; every value is overridable via environment variable
(`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD`, `SERVER_PORT`, `CORS_ALLOWED_ORIGINS`,
`FRAUD_REVIEW_THRESHOLD`).

### Frontend

```bash
cd frontend
cp .env.local.example .env.local   # NEXT_PUBLIC_API_BASE_URL, defaults to localhost:8080
npm install
npm run dev
```

Open http://localhost:3000.

### Running the backend tests

```bash
cd backend
./mvnw test
```

Requires Docker (Testcontainers starts a real `postgres:16-alpine` container per test run — no manual
Postgres needed). Covers the concurrency proofs above plus idempotency replay/conflict, the fraud
threshold, and the admin release/reject flow, all driven over real HTTP with `TestRestTemplate`.

## API reference

Base path: `/api/v1/ledger`

| Method | Path | Notes |
|---|---|---|
| `POST` | `/transfer` | Requires `Idempotency-Key` header. `201` on completion, `202` if flagged `PENDING_REVIEW`, `200`/original status on replay (with `Idempotent-Replay: true` header). |
| `GET` | `/accounts` | List all accounts with current balances. |
| `GET` | `/transactions?status=&page=&size=` | Paginated ledger, optional status filter. |
| `GET` | `/transactions/analytics/summary` | Transaction count/total grouped by status. |
| `POST` | `/admin/transactions/{id}/release` | Releases a `PENDING_REVIEW` transfer — moves the funds. |
| `POST` | `/admin/transactions/{id}/reject` | Rejects a `PENDING_REVIEW` transfer — no funds move. |

## Frontend dashboard

- **Accounts** — live balances, polled every 8s.
- **New transfer** — pick source/destination/amount/currency and send. A second button, **"Simulate
  duplicate (same key)"**, resends the exact same request with the exact same `Idempotency-Key` and
  reports whether the server treated it as a replay (`Idempotent-Replay` response header) — the
  concrete, visible proof that resubmission is safe.
- **Ledger** — paginated transaction table with a status filter; `PENDING_REVIEW` rows get inline
  Release/Reject actions.
- **Volume by status** — bar chart of transaction counts grouped by outcome.

## Project structure

```
backend/
  src/main/java/com/datakite/ledger/
    aspect/        FraudDetectionAspect (AOP fraud-review rule)
    config/        WebConfig (CORS + interceptor registration)
    controller/     LedgerController, AdminController
    dto/           Request/response records
    exception/     Domain exceptions + GlobalExceptionHandler
    interceptor/    IdempotencyKeyInterceptor
    model/          Account, Transaction, IdempotencyRecord, TransactionStatus
    repository/     Spring Data JPA repositories (incl. the FOR UPDATE lock query)
    service/        TransferService, TransferExecutionService, AccountLockService,
                     IdempotencyService, AdminReviewService, AccountService,
                     TransactionQueryService
  src/main/resources/db/changelog/   Liquibase changesets
  src/test/java/com/datakite/ledger/ Testcontainers-backed integration tests
frontend/
  src/app/                 Next.js App Router entry point
  src/components/dashboard/ Dashboard feature components
  src/components/ui/       shadcn/ui primitives
  src/lib/                 API client + types
docker-compose.yml
```
