# CETA — Khởi động dev với Codex trên repo có sẵn

> Tình huống: có 6 tài liệu đã chốt + `anhntnse/MovieTicketBackend` @ `50f12c0` (110 file Java, **build đỏ**, `build.log` commit lỗi).
> Câu hỏi: bắt Codex vào thì làm gì trước?

---

## 0. TL;DR — 2 giờ đầu, theo đúng thứ tự

| # | Việc | Thời gian | Xong khi |
|---|---|---|---|
| 1 | `git tag v0-audit` — đóng băng code cũ để luôn quay lại được | 1 phút | Tag tồn tại |
| 2 | Nhánh `baseline/green` + **xoá tầng hold cũ** (§2) | 30 phút | `mvn clean package` **xanh** |
| 3 | Commit `chore(baseline): green build without broken seat-hold layer` | 5 phút | `git log` sạch, 1 commit |
| 4 | Thêm `AGENTS.md` + `docs/BASELINE.md` | 10 phút | Codex đọc được hợp đồng |
| 5 | Chạy **Task 1** (Flyway + entity) qua Codex | 45 phút | Migration chạy trên MySQL trống |

**Nguyên tắc:** Codex chỉ giỏi khi nền đã xanh và đường biên đã rõ. Đưa repo đỏ cho nó = nó sửa lung tung để "làm cho build chạy".

---

## 1. Sự thật về repo hiện tại — vì sao không xây trực tiếp lên nó

| Vấn đề | Bằng chứng | Hệ quả nếu bỏ qua |
|---|---|---|
| Build đỏ | `build.log` commit: `Tests run: 1, Errors: 1`, 13/05/2026 | Codex sẽ "sửa" test bằng cách xoá assert |
| Tầng hold sai kiến trúc | `SeatReservationService.holdSeats()` = `hasKey()` loop rồi `set()` loop | Không atomic; đây là chỗ sinh bán trùng ghế |
| Có 2 nguồn sự thật | `SeatHold` entity tồn tại nhưng **không ai ghi**; chỉ job dọn đọc nó | Codex sẽ tưởng entity đó là thật và code theo |
| Thiếu config gốc | Chỉ có `application-dev.properties`, **không có** `application.properties` | Chạy production không có default → sai môi trường |
| `h2` ở compile scope | `pom.xml` dòng 96 | H2 lọt vào jar production |
| Không migration | Không có Flyway/Liquibase | Schema trôi, không tái lập được |

**Kết luận:** không viết lại từ 0 (mất 2 tuần), không xây trên code cũ (mang bug theo). Chọn **strip → green → seam**: xoá đúng tầng hỏng, đưa build về xanh, rồi xây module mới cạnh phần còn tốt.

**Cái giữ lại được:** `AuthServiceImpl` (refresh token rotation + logout revoke đã đúng), entity `Movie/Showtime/Room/Seat`, JWT filter, `GlobalExceptionHandler`, cấu trúc package.

---

## 2. Việc Day-0 — làm tay, KHÔNG giao Codex

### 2.1 Xoá tầng hold cũ

```bash
git checkout -b baseline/green
git rm -r --cached build.log && rm build.log
git rm src/main/java/com/movie/ticket_booking/entity/SeatHold.java
git rm src/main/java/com/movie/ticket_booking/repository/SeatHoldRepository.java
git rm src/main/java/com/movie/ticket_booking/service/SeatReservationService.java
git rm src/main/java/com/movie/ticket_booking/config/BookingHoldCleanupJob.java
git rm src/main/java/com/movie/ticket_booking/dto/booking/HoldSeatResponse.java \
       src/main/java/com/movie/ticket_booking/dto/booking/HoldSeatsRequest.java \
       src/main/java/com/movie/ticket_booking/dto/booking/ReleaseSeatsRequest.java
```

Sau đó `grep -rn "SeatHold\|holdSeats\|releaseSeats" src/` và xoá mọi endpoint/import còn trỏ vào chúng (thường ở `BookingController`, `BookingServiceImpl`).

### 2.2 Sửa `pom.xml` (3 thay đổi)

```xml
<!-- h2: đổi từ compile xuống test -->
<dependency>
  <groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>test</scope>
</dependency>

<!-- thêm Flyway -->
<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-mysql</artifactId></dependency>

<!-- thêm ArchUnit (giữ Codex trong khuôn) -->
<dependency>
  <groupId>com.tngtech.archunit</groupId><artifactId>archunit-junit5</artifactId>
  <version>1.3.0</version><scope>test</scope>
</dependency>
```

### 2.3 Thêm `src/main/resources/application.properties`

```properties
spring.application.name=ceta-backend
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
app.booking.hold-enabled=true
app.booking.hold-ttl=10m
app.booking.max-seats-per-booking=8
app.booking.sweeper-interval=20s
```

### 2.4 Đưa build về xanh — bằng cách xoá test của tầng đã xoá

Test trong `SeatControllerTest` / `BookingControllerTest` đang phụ thuộc `SeatReservationService` → xoá các method đó (không `@Disabled`, không sửa assert cho pass).

```bash
mvn -q clean package -DskipTests && mvn -q test   # cả hai phải xanh
```

### 2.5 Commit & gắn mốc

```bash
git add -A && git commit -m "chore(baseline): remove broken seat-hold layer, green build"
git tag v1-baseline-green
mkdir -p docs && cp CETA_00_BASELINE_v1.md docs/BASELINE.md
git add AGENTS.md docs/BASELINE.md && git commit -m "docs: add agent contract and baseline spec"
```

Từ đây **mọi thứ đi qua Codex đều bắt đầu từ `v1-baseline-green`**. Commit xấu → `git reset --hard v1-baseline-green`.

---

## 3. Ba thứ quyết định Codex làm đúng hay sai

| Thứ | Vì sao quan trọng |
|---|---|
| **`AGENTS.md`** | Codex tự đọc file này ở mỗi lần chạy. Đây là chỗ duy nhất bạn "lập trình" hành vi của nó mà không phải nhắc lại trong prompt |
| **Migration `V1__baseline.sql` do bạn viết tay** | Schema là hợp đồng. Cho Codex sinh schema = mỗi task một kiểu |
| **ArchUnit test** | Biến "đừng UPDATE showtime_seat ngoài module inventory" từ lời khuyên thành **lỗi build**. Codex không cãi được lỗi build |

Ba cái này có trước thì 9 task sau chạy rất nhanh. Không có thì mỗi task là một cuộc tranh luận.

---

## 4. Chín task Codex — theo đúng thứ tự, mỗi lần một task

> Cách dùng: mở Codex trong repo, dán **nguyên khối prompt** của task. Không dán hai task một lúc. Xong → review diff → commit → task tiếp.

### T1 — Migration + entity `showtime_seat`

```
Đọc AGENTS.md và docs/BASELINE.md §4, §5.
Tạo file src/main/resources/db/migration/V1__baseline.sql CHỈ chứa 5 bảng:
showtime_seat, booking, payment_transaction, refund_queue, booking_status_history
— copy DDL nguyên văn từ docs/BASELINE.md §4, không sáng tạo thêm cột.
Tạo entity JPA tương ứng trong package entity/ + enum SeatInventoryStatus (AVAILABLE/HELD/SOLD/BLOCKED)
và BookingStatus (PENDING/PAID/CANCELLED/EXPIRED/REFUNDED) trong entity/enums/.
Entity không dùng @Data (dùng @Getter/@Setter), quan hệ dùng @ManyToOne(fetch=LAZY).
Chạy: mvn -q clean test. Dán output.
Không sửa file nào khác.
```

**Nghiệm thu:** `flyway_schema_history` có V1; `SHOW CREATE TABLE showtime_seat` có `ck_held`.

### T2 — `InventoryService` + ArchUnit

```
Đọc AGENTS.md §2, §4 và docs/BASELINE.md §6.
Tạo package service/inventory/ gồm:
- InventoryService (interface): claim(showtimeId, seatIds, bookingId, expiresAt),
  release(bookingId), promoteToSold(bookingId), releaseExpired()
- InventoryServiceImpl: claim dùng @Lock(PESSIMISTIC_WRITE) hoặc query
  "SELECT id FROM showtime WHERE id=:id FOR UPDATE", đếm ghế AVAILABLE,
  nếu != size thì ném SeatConflictException(conflictSeatIds),
  rồi UPDATE ... WHERE status='AVAILABLE' và assert affectedRows == size.
- SeatConflictException trong exception/, map sang HTTP 409 code SEAT_CONFLICT ở GlobalExceptionHandler.
Tạo ArchUnit test: không class nào ngoài service.inventory được gọi repository của ShowtimeSeat.
Chạy: mvn -q test. Dán output.
```

**Nghiệm thu:** unit test 3 case (đủ ghế / thiếu ghế / release idempotent); ArchUnit đỏ nếu cố tình UPDATE từ chỗ khác.

### T3 — Test đồng thời (viết TRƯỚC, phải đỏ trước khi xanh)

```
Đọc AGENTS.md §6.
Tạo src/test/java/.../inventory/ConcurrentClaimTest.java:
- @SpringBootTest, profile "it-mysql", 50 thread dùng CountDownLatch bắn claim
  cùng 1 showtime + cùng 1 ghế.
- Assert: đúng 1 thread thành công, 49 thread nhận SeatConflictException,
  và count(showtime_seat.status='HELD') == 1.
Chạy: mvn -q -Pit-mysql test -Dtest=ConcurrentClaimTest. Dán output.
Nếu chưa có profile it-mysql thì tạo, trỏ tới MySQL local (biến môi trường IT_MYSQL_URL).
```

**Đây là Gate A.** Nếu test này không xanh, không được sang T4.

### T4 — `POST /bookings` (claim ngay)

```
Đọc docs/BASELINE.md §6 F2, §7.
Implement POST /api/v1/bookings theo đúng thứ tự: khoá showtime → đếm AVAILABLE
→ update → insert booking(PENDING) → insert booking_seat → commit.
Validate: ≤ 8 ghế (D-16), trong cửa sổ bán T-14 (D-17), ghế đôi phải đủ cặp (D-18),
user chưa có booking PENDING cho suất này (D-02 — trả 409 BOOKING_ALREADY_PENDING).
Response: {bookingId, holdExpiresAt, totalAmount, seats[]}. Lỗi: 409 SEAT_CONFLICT + details[].
Chạy: mvn -q test. Dán output.
```

### T5 — `DELETE /bookings/{id}` + `GET /bookings/{id}`

```
Đọc docs/BASELINE.md §6 F4, F5, §7.
DELETE: chỉ owner + status PENDING → inventory.release(bookingId) + booking CANCELLED.
GET: trả status, holdExpiresAt, secondsRemaining, tickets[].
Phân quyền: user chỉ thấy booking của mình (403 nếu không).
Chạy: mvn -q test. Dán output.
```

### T6 — VNPay IPN + return

```
Đọc docs/BASELINE.md §6 F3, D-09→D-12.
Implement POST /api/v1/payments/vnpay/ipn và GET .../return, cùng gọi một
ApplyPaymentResultUseCase:
1. UPDATE payment_transaction SET processed_at=NOW() WHERE txn_ref=:ref AND processed_at IS NULL
   — affectedRows=0 → return 200 luôn (idempotent).
2. Verify chữ ký HMAC-SHA512, vnp_Amount == booking.totalAmount, vnp_TmnCode == config.
   Sai một trong ba → từ chối + log SECURITY.
3. inventory.promoteToSold(bookingId); nếu thất bại → refund_queue(LATE_PAYMENT).
4. booking PENDING→PAID, sinh ticket, assert Σ ticket.price == totalAmount.
Test: gọi IPN 5 lần cùng payload → đúng 1 ticket.
Chạy: mvn -q test. Dán output.
```

### T7 — `refund_queue` + nhánh muộn

```
Đọc docs/BASELINE.md §4 refund_queue, §5.
Implement: ghi refund_queue (unique booking_id, ON DUPLICATE → bỏ qua),
service đọc danh sách PENDING, endpoint admin PUT /admin/refunds/{id}/done (role ADMIN).
Test: seed booking EXPIRED rồi bắn IPN muộn → 1 dòng refund_queue, 0 ticket.
Chạy: mvn -q test. Dán output.
```

### T8 — Sweeper + kill switch

```
Đọc docs/BASELINE.md §6, D-06, D-08.
Sweeper @Scheduled(fixedDelayString="${app.booking.sweeper-interval}"):
UPDATE showtime_seat SET status='AVAILABLE', held_until=NULL, booking_id=NULL
WHERE status='HELD' AND held_until < NOW();
UPDATE booking SET status='EXPIRED' WHERE status='PENDING' AND hold_expires_at < NOW();
Cùng transaction, mỗi câu 1 lần. Ghi booking_status_history.
Kill switch: nếu app.booking.hold-enabled=false thì POST /bookings KHÔNG claim,
và IPN claim từ AVAILABLE (giữ nhánh cũ).
Test cả hai chế độ. Chạy: mvn -q test. Dán output.
```

### T9 — Siết phân quyền

```
Đọc AGENTS.md §5.
Bỏ fallback hasRole('ADMIN') cho /api/**. Thêm @PreAuthorize từng endpoint:
public read = permitAll, booking = authenticated, admin = hasRole('ADMIN'),
VNPay IPN/return = permitAll (chữ ký là auth).
Viết test 403 cho từng nhóm. Chạy: mvn -q test. Dán output.
```

---

## 5. Review diff của Codex — 6 câu, hỏi nhanh

1. Nó có thêm `RedisTemplate` / `hasKey` / `ConcurrentHashMap` vào đường giữ ghế không? → **revert**.
2. `SELECT ... FOR UPDATE` có nằm **trong** transaction không? (kiểm annotation ở đúng class, không phải method private)
3. Có gọi `pricingService.calculate()` ở bước claim không? → **revert** (vi phạm D-12).
4. Có sửa `V1__baseline.sql` / `pom.xml` / `AGENTS.md` không? → **revert**.
5. Diff có lan sang file không liên quan không? → tách commit.
6. Test mới có khẳng định **hành vi** hay chỉ khẳng định "không ném exception"? → viết lại.

**Đừng đọc toàn bộ diff nếu diff > 400 dòng.** Diff lớn = task quá to → tách nhỏ lại, không review.

---

## 6. Hai chỗ phải tự viết, không giao Codex

| Chỗ | Vì sao |
|---|---|
| `InventoryService.claim()` | Là nơi tiền và tồn kho gặp nhau. Codex viết được nhưng bạn phải hiểu từng dòng — đây là chỗ duy nhất bạn không được "tin rồi kiểm sau" |
| `ApplyPaymentResultUseCase` | Quyết định ai được nhận vé, ai phải hoàn tiền. Sai ở đây = mất tiền thật |

Codex viết test, viết DTO, viết CRUD, viết admin screen — tốt. Hai hàm trên thì nó viết bản nháp, bạn viết lại.

---

## 7. Lịch 10 ngày, khớp baseline

| Ngày | Task | Gate |
|---|---|---|
| D0 (hôm nay) | §2 strip → green → commit → AGENTS.md | `mvn test` xanh |
| D1 | T1 | Flyway V1 chạy được |
| D2 | T2 | Unit + ArchUnit xanh |
| D3 | T3 | **Gate A: 50 thread → 1 thắng** |
| D4 | T4, T5 | 409 có `details[]` |
| D5 | T6 | IPN 5 lần → 1 vé |
| D6 | T7 | Late IPN → refund, 0 vé |
| D7 | T8 | Kill switch test xanh |
| D8 | T9 | Bảng quyền đủ |
| D9–D10 | Admin screen + staging + VNPay sandbox | **Gate B** |

---

## 8. Nếu chỉ nhớ một điều

**Codex không bị chặn bởi tài liệu dài, nó bị chặn bởi repo đỏ và task mơ hồ.** Nên thứ tự đúng là: repo xanh → `AGENTS.md` → migration tay → test đồng thời viết trước → rồi mới để nó code. Tài liệu 6 file chỉ có tác dụng khi 4 bước đó đã xong.
