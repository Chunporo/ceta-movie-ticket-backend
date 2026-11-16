# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**`AGENTS.md` is the binding contract — read it before editing** (invariants P0, forbidden patterns, protected files, one-task-one-commit). Business source of truth is `docs/BASELINE.md`; it wins over every other doc. Docs and task prompts are in Vietnamese. Other files in `docs/` are history/context only.

## Commands

```bash
mvn -q clean compile                 # must be green before starting any task
mvn -q test                          # unit tests (H2, no MySQL needed)
mvn -q test -Dtest=ClassName#method  # single test
./scripts/verify.sh                  # REQUIRED before reporting done; greps forbidden patterns,
                                     # checks protected files unmodified, runs mvn clean test
IT_MYSQL_URL=jdbc:mysql://localhost:3306/ceta mvn -q -Pit-mysql test -Dtest=ConcurrentClaimTest
```

- The `it-mysql` profile in `pom.xml` runs only tests tagged `@Tag("it-mysql")` (default runs exclude them); it reads `IT_MYSQL_URL`, `IT_MYSQL_USER` (default root), `IT_MYSQL_PASSWORD` (default root). Row-locking tests need real MySQL (H2 doesn't emulate `FOR UPDATE`); local MySQL: `docker run -d --name ceta-mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=ceta mysql:8`.
- Tests must never hit real VNPay — mock `VnPayClient`.
- Report real command output, not "it passes".

## Workflow

- Work is driven by `TASKS.md` (T0–T10, strict order, gates). Each task has a prompt in `prompts/Tn-*.md`; do exactly one task per session and one commit per task. T0–T6 are done (Gate A passed); T7 (`refund_queue`) is next. Payment callbacks go through `service/payment/ApplyPaymentResultUseCase` (IPN `/api/v1/payments/vnpay/ipn`, return `/api/v1/payments/vnpay/return`). Nothing creates `payment_transaction` rows yet (no create-url endpoint), and the legacy `/api/payment/vnpay/callback` still exists. New booking API lives under `/api/v1/bookings` (`BookingV1Controller` → `BookingCreationService` / `BookingLifecycleService`); the legacy `/api/bookings/checkout` flow is still present and predates the hold model.
- Schema changes go in a new Flyway migration (`V2__...`); never edit `V1__baseline.sql`. `ddl-auto` must stay `validate`.
- If the baseline looks wrong or incomplete, stop and report rather than deciding.
- Don't commit `application-local.properties`, `*.jks`, `build.log`, `target/`. Adding dependencies requires asking first.

## Architecture

Single Spring Boot 3.4 / Java 21 monolith (base package `com.movie.ticket_booking`), MySQL 8 + Flyway, Redis for read caching only (never locks or source of truth), JWT, VNPay, config in `application.properties` (+ `application-dev.properties` for Swagger/SMTP).

The core design is **seat inventory with hold** (BASELINE D-01..D-08):

- `service/inventory/InventoryService` is the only code allowed to touch the `showtime_seat` table. `claim` locks the `showtime` row (`SELECT ... FOR UPDATE`) then does `UPDATE ... WHERE status='AVAILABLE'` and requires affected rows == n (all-or-nothing, else 409 with conflicts). `release`, `promoteToSold`, `releaseExpired` (sweeper) complete the lifecycle. These methods join the caller's transaction so booking status changes commit atomically with seat changes.
- The boundary is enforced by ArchUnit (`architecture/InventoryBoundaryTest`): no class outside `..service.inventory..` may depend on `ShowtimeSeatRepository`. Booking/payment code must go through `InventoryService`.
- Booking flow: create booking (total price frozen at creation, never recomputed) → seats `HELD` for 10 min → VNPay IPN (primary) / return URL (same use case) → idempotent via `payment_transaction.processed_at` → tickets issued; sweeper (every 20s) expires holds. Late IPN → `refund_queue`.
- Kill switch `app.booking.hold-enabled` (plus `hold-ttl`, `max-seats-per-booking`, `sweeper-interval`) falls back to claim-at-IPN.
- Errors are `BusinessException(code)` / `SeatConflictException` rendered by `GlobalExceptionHandler` as `{code,message,details[]}`; `code` strings are fixed by BASELINE §7.
- The repo is mid-refactor: the old seat-hold layer was removed (T0) and `verify.sh` fails if `SeatHold`/`SeatReservationService`/`holdSeats` reappear. Some packages named in AGENTS.md (e.g. `security/`) may not exist yet — check before assuming.
- Tests: `migration/` (Flyway baseline + entity persistence), `architecture/` (ArchUnit), `controller/` (web slice), `exception/`.
