# T1 schema alignment

The five definitions in `BASELINE.md` section 4 take precedence over the older
architecture DDL. V1 copies them verbatim. Existing Hibernate mappings were
exported with MySQLDialect to supply the other tables and existing constraints.

- `Showtime`, `Seat`, and `Booking` now map to `showtime`, `seat`, and `booking`
  to match the baseline foreign keys and booking definition. Other legacy table
  and column names remain unchanged, including `tickets` and its unique
  `(showtime_id, seat_id)` constraint.
- Booking amounts use `Long` VND. The existing checkout adapter uses exact
  conversion from legacy decimal pricing and supplies the required expiry from
  `app.booking.hold-ttl`. Booking response totals use `Long` as well. Ticket and
  pricing mappings are retained as required by T1.
- The baseline booking has no payment-provider, payment-reference, contact-email,
  or updated-at columns. They are removed from its entity rather than silently
  added to V1. Confirmation email uses the account email. Legacy checkout request
  provider/contact-email fields no longer select a persisted booking value.
  Payment metadata belongs in `payment_transaction`; the payment use case is T6.
- A single cinema is implicit in the deployment: there is no cinema entity or
  cinema table in the supplied schema. V1 seeds one room, A1–H15, one film,
  tomorrow's UTC showtime, and 120 AVAILABLE inventory rows. The existing Java
  demo seeder can add its own fixtures after application startup.
- `booking_seat` is referenced by the baseline and addendum but has no supplied
  DDL. T1 explicitly adds five baseline tables, so it does not invent a sixth.
  Define the price-snapshot table in a new migration before implementing T4.
- The verification script permits creating an untracked V1 for T1. Once V1 is
  tracked, modifications are still rejected. Baseline and agent instructions
  are not changed.

## Verification

Ordinary `mvn -q clean test` uses the existing H2 test configuration and does not
need MySQL. Persistence tests exercise inventory identity, booking amounts,
payment JSON, refund defaults, audit timestamps, and detached email access.

The opt-in real MySQL test requires an isolated database (it migrates the database
and rolls back its constraint-test data):

```bash
T1_MYSQL_URL='jdbc:mysql://localhost:33316/ceta_t1?serverTimezone=UTC' \
T1_MYSQL_USER=ceta T1_MYSQL_PASSWORD='<local password>' \
mvn -q test -Dtest=BaselineMigrationTest
```

It verifies seed rows, a second no-op migration, foreign keys, `ck_held`, ticket
uniqueness, and the generated pending-booking key. Application startup is checked
separately against an empty MySQL 8 database with Flyway enabled and Hibernate
`validate`; JWT, datasource, and VNPay configuration are supplied via environment
variables. No live VNPay call is required.
