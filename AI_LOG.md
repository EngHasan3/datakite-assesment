# AI Development Log

**Start:** 2026-07-01, ~18:30 (local)
**Finish:** 2026-07-01, ~21:00 (local)
**Active hands-on time:** ~4.5 hours

## 1. AI tooling stack

- **Claude Code (Sonnet 5)** — the sole tool used for this build. It wrote every backend and frontend
  file, ran the Maven/npm builds, drove Testcontainers-based integration tests, and — critically —
  actually launched the app (Docker Compose, `mvnw spring-boot:run`, `next dev`) and drove it with a
  real headless browser (Playwright) to visually verify the dashboard rather than trusting that the
  code "looked right."
- **A second-opinion "advisor" pass** was used at three points: before writing any code (architecture
  review), mid-build when a test failed unexpectedly (see §3), and before declaring the backend done.
  This caught two of the bugs below *before* I ever ran a test, and correctly predicted a third.
- No other AI code-gen tool (no Copilot/Cursor) was used — this was a single continuous Claude Code
  session, with me (the engineer) reviewing every diff, running the test suite, and directing the next
  step.

## 2. Prompt engineering strategy

I did not ask for the whole app in one shot. The prompts that mattered most were the ones that forced
an explicit design decision *before* code got written, and the ones that asked for verification rather
than more code.

**Locking mechanism — asking for the failure mode, not just the feature:**
> "Implement optimistic locking (JPA @Version) or pessimistic locking on Account balance updates.
> Demonstrate how the system prevents double-spending... when multiple concurrent transfer requests
> hit the same source account within milliseconds." — paraphrased from the assessment brief, given to
> the advisor before implementation.
>
> Advisor's response (verbatim excerpt): *"A test asserting 'N successful transfers sum correctly'
> doesn't prove the guard. The one that does: fire more concurrent debits at one source account than
> its balance supports, assert exactly floor(balance/amount) succeed... and the balance never goes
> negative."*

That reframing — from "test that transfers work" to "test that exactly `floor(balance/amount)`
succeed and no more" — is the actual assertion in `TransferConcurrencyTest`. It's a stronger claim than
"no errors occurred," and it's what makes the test deterministic instead of flaky.

**Frontend — asking for the interaction that proves the requirement, not just a form:**
The brief asked for "an option to simulate duplicate requests — testing the Idempotency-Key header."
I translated that into a concrete UI contract before writing `TransferForm`: a "Send transfer" button
that always mints a fresh key, and a second, separately-gated "Simulate duplicate (same key)" button
that is only enabled when the form still matches the last submission byte-for-byte, and which surfaces
the server's `Idempotent-Replay` response header directly in a toast. The point was to make the
guarantee *visible* — a user should be able to watch a duplicate get caught, not just trust that it
was.

**Human-in-the-loop as a designed step, not an afterthought:**
Before writing `TransferService`, I asked the advisor to review the planned design (pessimistic
row-locking with fixed lock ordering, a separate `IdempotencyRecord` table used as a claim/mutex,
`@Aspect`-based fraud interception) *before* any of it was implemented. It flagged two specific,
concrete risks by name — AOP self-invocation bypassing the fraud aspect, and a transaction-poisoning
race in the idempotency claim/insert — which is exactly what shaped the two-bean split
(`TransferService` orchestrator vs. `TransferExecutionService` executor) and the `REQUIRES_NEW`
propagation on `IdempotencyService`. Both risks were real; see §3.

## 3. Human-in-the-loop validation — bugs the AI introduced and how they were caught

Seven real issues surfaced during this build — five correctness bugs, one UI regression, and one build
performance problem. All were caught by *running* something (a test, a curl request, a real browser, or
a timed build) — none were caught by re-reading the code.

### 3.1 A Hibernate "load-then-lock" bug that would have caused silent double-spending

**What happened:** `TransferExecutionService.executeTransfer` originally resolved account numbers to
full `Account` entities via an unlocked `findByAccountNumber` query, then separately locked those same
rows by id via a `SELECT ... FOR UPDATE` query.

**How it was caught:** `TransferConcurrencyTest.concurrentTransfersFromSameAccountNeverOverdraw` fired
30 concurrent $100 transfers against a $1,000 balance and asserted exactly 10 succeed. It failed —
only 3 succeeded, the rest came back `409 CONFLICT` ("account was updated concurrently"). That 409 is
`Account.@Version` doing its job as a backstop, which is *also* what made the bug detectable instead of
silent.

**Root cause:** the row-level `FOR UPDATE` lock was being acquired correctly at the database level, but
because an *unlocked* `Account` for the same id was already sitting in the same request's Hibernate
persistence context (identity map) from the earlier lookup, the later `FOR UPDATE` query returned the
*same cached Java object* instead of refreshing it from the fresh row Postgres had just unblocked. The
code computed `balance.compareTo(amount)` and the debit against **stale, pre-wait balance data**, even
though the database-level lock was working exactly as intended. Removing `@Version` to "fix" the
symptom would have been the wrong fix — it would have converted a loud, detectable 409 into a silent
lost-update double-spend, which is precisely the failure this assessment is testing for.

**Fix:** added `AccountRepository.findIdByAccountNumber`, an id-only projection that never
materializes an `Account` entity, so the *only* entity load for a given account in a transfer is the
locked one. `AccountLockService`'s Javadoc now documents the trap so no future write path reintroduces
it.

### 3.2 Spring Data JPA `save()` silently defeating a unique-constraint mutex

**What happened:** `IdempotencyService.claim()` used `repository.saveAndFlush(placeholder)` to insert a
placeholder row, relying on the primary key's uniqueness as a mutual-exclusion device for concurrent
requests sharing an `Idempotency-Key`.

**How it was caught:** `TransferApiTest.reusingSameKeyWithDifferentPayloadIsRejected` expected `409` and
got `422`; a companion replay test got a `500`. Backend logs showed the *transactions* table's unique
constraint firing on `idempotency_key` — meaning the transfer had actually executed twice.

**Root cause:** `IdempotencyRecord.idempotencyKey` is a manually-assigned (non-generated) `@Id`. Spring
Data JPA's default `isNew()` heuristic for such ids can't tell "this is a brand new row" from "this id
already exists," so `save()` calls `entityManager.merge()` — an upsert — instead of a real `INSERT`. A
second `claim()` for the same key didn't throw; it silently overwrote the first request's row with a
blank placeholder, and both concurrent requests proceeded to execute the transfer.

**Fix:** `IdempotencyRecord` now implements `Persistable<String>` with `isNew()` hardcoded `true`, which
forces `save()` to always call `persist()` (a genuine insert that fails loudly on a duplicate key).

### 3.3 CORS preflight blocked by a header-enforcing interceptor

**What happened:** `curl` testing of every endpoint passed. The dashboard's "Send transfer" button
failed silently with a browser CORS error the moment it was driven in an actual browser.

**How it was caught:** ran the frontend dev server and drove it with a headless-Chromium Playwright
script (`curl` doesn't send CORS preflight `OPTIONS` requests, so this class of bug was invisible until
something in a real browser context clicked the button). The console showed: *"Response to preflight
request doesn't pass access control check: It does not have HTTP ok status."*

**Root cause:** `IdempotencyKeyInterceptor` enforced the `Idempotency-Key` header on every request to
`/transfer`, including the browser's `OPTIONS` preflight — which, by the CORS spec, never carries the
application's custom headers. The interceptor threw `MissingIdempotencyKeyException` on the preflight
itself, turning it into a 500 and failing the whole request before the real `POST` was ever sent.

**Fix:** `preHandle` now returns `true` immediately for `OPTIONS` requests.

### 3.4 (Related) A custom response header silently stripped by the browser

Once 3.3 was fixed, the "Simulate duplicate" button worked functionally (the backend correctly served
the cached response — the transactions table showed exactly one row) but the frontend toast always
reported "not a replay," which would have read as a real bug in the idempotency guarantee. It wasn't:
`Idempotent-Replay` is a custom response header, and browsers strip non-safelisted response headers
from JavaScript's view of `fetch()` unless the server explicitly lists them in
`Access-Control-Expose-Headers`. Fixed with `.exposedHeaders("Idempotent-Replay")` in `WebConfig`. This
one only cost a wrong toast message, but it's the same class of "curl can't see this" issue as 3.3 —
worth calling out because it would have been very easy to ship a UI that *looks* broken while the
backend guarantee is actually intact.

### 3.5 A revert that deleted CORS along with the feature it was reverting (2026-07-02 follow-up)

An OAuth2/Keycloak integration was added on top of the finished assessment build (a separate resource
server + login flow), then later removed at the reviewer's request since it wasn't part of the brief.
Reverting it meant deleting `SecurityConfig.java` — but that class had *also* absorbed `WebConfig`'s
original `addCorsMappings` override when the security layer was added (a reasonable refactor at the
time: Spring Security commonly owns CORS once a `SecurityFilterChain` exists, to avoid two competing
CORS configurers). Deleting the file to remove auth silently deleted CORS with it, and nothing caught
this at revert time — the backend compiled, all 9 backend tests passed (they call the API directly with
`TestRestTemplate`, which never triggers a browser's CORS preflight), and `docker compose up --build`
came up healthy.

**How it surfaced:** the user reported the dashboard showing "Could not reach the ledger API," the
source/destination/currency dropdowns rendering with no accounts to pick from, and the dropdowns looking
"misaligned." All three turned out to be the *same* bug, not three independent ones: with `GET
/accounts` blocked by a `403 Invalid CORS request`, the `accounts` array in `page.tsx` never populated,
so the Source/Destination `<Select>`s had zero items — same failure as `AccountsOverview`'s "Could not
reach" banner, and the "alignment" issue was really an empty dropdown next to Currency's always-populated
one, not a CSS bug at all.

**Root cause, confirmed with `curl`:** `curl -X OPTIONS .../accounts -H "Origin: http://localhost:3000"
-H "Access-Control-Request-Method: GET"` returned `403 Invalid CORS request` — Spring MVC had no CORS
configuration registered for that path at all.

**Fix:** restored `addCorsMappings` on `WebConfig` (the `IdempotencyKeyInterceptor`'s registration was
untouched by the auth revert and stayed correct throughout). Verified by rebuilding the backend
container, re-running the OPTIONS `curl` (now `200` with the right `Access-Control-Allow-*` headers), and
re-driving the full dashboard in Playwright: accounts populate, both dropdowns show all 5 accounts with
matching widths, and a real transfer completes end to end.

**Lesson:** a revert is a code change like any other and deserves the same "run something real" scrutiny
as new code — `mvn test` and a compiling Docker build both looked green because neither one drives the
browser-only code path (CORS preflight) that the deleted config was protecting. This is the second time
in this log that Testcontainers/`TestRestTemplate` correctness and actual browser correctness diverged
(see 3.3, 3.4); the fix going forward is to always re-run the Playwright smoke pass after touching
`WebConfig`, `SecurityConfig`, or anything CORS-adjacent, not just the backend test suite.

### 3.6 CSS Grid's default `align-content` silently misaligning a form field

**What happened:** the transfer form's four fields (Source, Destination, Amount, Currency) each wrap
their `<Label>` + control in a `<div className="grid gap-1.5">`. The Amount field has an extra helper
paragraph below its input ("Amounts over $5,000 are automatically flagged...") that Currency doesn't
have, making Amount's own grid cell taller — an entirely ordinary one-line-vs-two-line difference.

**How it was caught:** the user reported the dropdowns looking "misaligned" — subtle enough (an ~11px
offset) that it was worth measuring rather than guessing. `page.locator("#currency").boundingBox()` vs
`#amount` gave `y: 679` vs `y: 668`, and `label[for="currency"]` measured 25px tall against
`label[for="amount"]`'s 14px — the real tell, since two single-word labels have no legitimate reason to
differ in height.

**Root cause:** in a CSS grid container, the default `align-content` computes to `stretch` for
auto-sized rows. The outer form grid stretches the *Currency* field's wrapper div to match the *Amount*
row's extra height, since they're siblings in the same grid row. That wrapper is itself `display: grid`
with two implicit rows (Label, Select) and no explicit `align-content: start` — so the extra height
handed down by the outer grid didn't sit as empty space after the Select, it got distributed *between*
the Label and the Select, pushing the Select down.

**Fix:** changed the four field wrappers from `grid gap-1.5` to `flex flex-col gap-1.5`. Flexbox's main
axis (vertical, for `flex-col`) does not redistribute leftover space among children by default
(`justify-content: flex-start`), so extra height from a taller sibling row stays empty space *after* the
last child instead of stretching every child. Re-measured after the fix: both selects' `y` and both
labels' height matched exactly.

**Lesson:** `display: grid` on a simple two-item vertical stack (label + control) isn't the "safe
default" it looks like — it silently opts into space-distribution behavior that `flex flex-col` doesn't
have. Field wrappers in this codebase should default to `flex flex-col`, not `grid`, unless a wrapper
genuinely needs grid's two-dimensional layout.

### 3.7 A Dockerfile that re-downloaded the entire dependency tree on every `pom.xml` edit

**What happened:** `backend/Dockerfile`'s build stage was `COPY pom.xml .` → `RUN mvn
dependency:go-offline` → `COPY src src` → `RUN mvn package`. Standard Docker layer caching means any
change to `pom.xml` invalidates that layer and everything after it, which is correct and expected. What
wasn't accounted for: Maven's local repository (`~/.m2`) lives *inside* that same invalidated
build-stage filesystem, so "re-run `dependency:go-offline`" meant re-downloading the *entire* dependency
tree from Maven Central every time, not just the one dependency that actually changed.

**How it was caught:** the user reported `docker compose up --build` appearing to hang and getting
cancelled on the `dependency:go-offline` step, and asked why it was slow "again." The immediate cause
was that `pom.xml` had genuinely changed minutes earlier (the OAuth2/Keycloak dependency removal), which
is expected to invalidate that layer — but a full multi-minute re-download on every future edit from here
on isn't something a working dev loop should have to accept.

**Fix:** added a BuildKit cache mount — `RUN --mount=type=cache,target=/root/.m2 mvn ...` — to both
Maven `RUN` steps, plus the `# syntax=docker/dockerfile:1` directive the mount syntax requires. This
cache lives outside the image layers entirely (managed by BuildKit on the machine running the build), so
it survives even when the `pom.xml` layer is invalidated. Measured before/after on a real `pom.xml` edit:
`dependency:go-offline` went from 43.7s to 2.3s, full image build from ~52s to ~7.4s.

**Why this doesn't hurt portability:** a cache mount is not part of the built image and isn't committed
anywhere. A machine that has never built this project starts with an empty cache and behaves exactly as
before — full download, same as any fresh `npm install` would need. The optimization only helps repeat
builds *on the same machine*, which is exactly the dev-loop pain that was reported.

---

**Takeaway:** every one of these seven issues lived in code (or a Dockerfile) that looked correct on
inspection and, in most cases, passed a naive test. The concurrency ones only surfaced under genuine
concurrent load against a real database (Testcontainers + Postgres, not H2, not mocks); the CORS ones
(3.3–3.5) only surfaced by actually opening a browser — `curl` and `TestRestTemplate` both bypass CORS
preflight entirely, so a backend that's 100% green on `mvn test` can still be completely unreachable from
a real frontend; the layout bug (3.6) only surfaced by measuring real pixel coordinates instead of
eyeballing a screenshot; the build-speed problem (3.7) only surfaced by actually timing a rebuild. None of
these were caught by re-reading the code — every one required running something real and looking at what
it actually did, not what it was supposed to do.
