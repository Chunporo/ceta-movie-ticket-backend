# CETA Cinema – Movie Ticket Backend

A personal project: the backend for a cinema ticket-booking system. The Android client lives in a separate repo (`CetaAndroid`, Kotlin + Jetpack Compose).

**Stack:** Spring Boot 3.5, Java 21, MySQL 8 + Flyway, Redis (read cache only), JWT + Firebase sign-in, STOMP/WebSocket.

## What it does

- **Catalog:** cinemas, rooms, movies, showtimes (2D/3D/IMAX, sub/dub), combos, offers and vouchers.
- **Seat inventory with hold:** seats are held for 10 minutes while the customer pays; a sweeper frees expired holds. Seat changes are pushed live over STOMP.
- **Bookings:** price is frozen at creation. Money invariant: `tickets + combos − voucher − points = total`.
- **Payments:** VNPay, MoMo and VietQR bank transfer, all settled through one idempotent use case. A late payment goes to a refund queue.
- **After payment:** tickets with signed QR codes, staff check-in, customer cancel, showtime cancellation, notifications, loyalty points, reviews and reminders.
- **Accounts:** email/password, Firebase (Google, phone), lockout after 5 bad passwords, password reset, account deletion.

## Run it

```bash
# MySQL and Redis
docker run -d --name ceta-mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=ceta mysql:8
docker run -d --name ceta-redis -p 6379:6379 redis:7

# Backend (dev profile adds Swagger)
sh ./mvnw spring-boot:run -Dspring-boot.run.profiles=local,dev
```

Put secrets and local settings in `src/main/resources/application-local.properties` (git-ignored).

## Tests

```bash
sh ./mvnw -q test              # unit tests on H2, no MySQL needed
./scripts/verify.sh            # forbidden-pattern checks + full test run
IT_MYSQL_URL=jdbc:mysql://localhost:3306/ceta sh ./mvnw -q -Pit-mysql test   # row-locking tests on real MySQL
```

## Trying payments without merchant accounts

Each payment method is off until its properties are set. Local simulators stand in for the gateways:

| Method | Simulator | Notes |
|---|---|---|
| VNPay | `scripts/vnpay_sim.py` (port 8099) | signed return and IPN |
| MoMo | `scripts/momo_sim.py` (port 8098) | `momo.*` properties |
| VietQR | `scripts/vietqr_sim.py CETA5A1 170000` | `vietqr.bank-id`, `account-no`, `webhook-key`; `--style sepay` for the generic webhook |

The VNPay, MoMo and VietQR payloads are built from public docs and checked only against these simulators, not against real accounts.

## Docs

- `AGENTS.md` – rules for working in this repo.
- `CLAUDE.md` – detailed architecture notes, endpoint by endpoint.
- `docs/BASELINE.md` – business source of truth.
- `docs/CETA_Android_Spec.md` – the API contract the app uses.
