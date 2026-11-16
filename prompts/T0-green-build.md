# T0 — Đưa build về xanh (bỏ tầng hold cũ)

Đọc `AGENTS.md` trước. Đây là task **dọn dẹp**, không viết tính năng.

## 1. Xoá các file sau (`git rm`)

```
src/main/java/com/movie/ticket_booking/entity/SeatHold.java
src/main/java/com/movie/ticket_booking/repository/SeatHoldRepository.java
src/main/java/com/movie/ticket_booking/service/SeatReservationService.java
src/main/java/com/movie/ticket_booking/config/BookingHoldCleanupJob.java
src/main/java/com/movie/ticket_booking/dto/booking/HoldSeatResponse.java
src/main/java/com/movie/ticket_booking/dto/booking/HoldSeatsRequest.java
src/main/java/com/movie/ticket_booking/dto/booking/ReleaseSeatsRequest.java
build.log          (git rm --cached rồi xoá file)
```

Sau đó `grep -rn "SeatHold|holdSeats|releaseSeats" src/` và xoá mọi endpoint/import còn trỏ vào chúng.

## 2. Sửa `pom.xml`

- `h2`: thêm `<scope>test</scope>`
- thêm `org.flywaydb:flyway-core` và `org.flywaydb:flyway-mysql` (không ghi version, parent quản lý)
- thêm `com.tngtech.archunit:archunit-junit5:1.3.0` với `<scope>test</scope>`

## 3. Tạo `src/main/resources/application.properties`

```properties
spring.application.name=ceta-backend
spring.jpa.hibernate.ddl-auto=none
spring.jpa.open-in-view=false
spring.flyway.enabled=false
spring.jpa.properties.hibernate.jdbc.time_zone=UTC
app.booking.hold-enabled=true
app.booking.hold-ttl=10m
app.booking.max-seats-per-booking=8
app.booking.sweeper-interval=20s
```

`ddl-auto=none` và `flyway.enabled=false` ở bước này là **cố ý** — T1 sẽ bật lại `validate` sau khi có migration.
Nếu test nào cần schema, tạo `src/test/resources/application-test.properties` dùng H2 với `ddl-auto=create-drop`. **Không** đổi `application.properties` thành `update`.

## 4. Sửa test bị hỏng do xoá class

Xoá **method test** không còn compile được. Không `@Disabled`, không nới assert, không viết test giả.

## Cấm

- Không đổi sang `ddl-auto=update`.
- Không thêm dependency khác ngoài 3 cái ở §2.
- Không format lại / rename file không liên quan.

## Xong khi

```bash
mvn -q clean test        # phải xanh
./scripts/verify.sh      # phải PASS (mục 1,2,3)
```

Dán output thật. Commit: `chore(baseline): remove broken seat-hold layer, green build`
