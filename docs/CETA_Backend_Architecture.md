# CETA MovieTicket — Backend Architecture Design
### Từ Overview đến Detail (L0 → L4)

> ⚠️ **BASELINE v1.0 — 16/11/2026.** File này đã được patch theo `CETA_00_BASELINE_v1.md`.
> **Nơi nào còn mâu thuẫn, baseline thắng.** Các mục **body còn mô tả mô hình cũ (bỏ hold)**: §4.2 · §6.2 (phần diễn giải) · §6.3 (phần diễn giải) · §6.5 · §7.1 · §8.5 (WS event HELD) · §14 dòng 2,3.
> Đọc phần thay thế tại: `CETA_00_BASELINE_v1.md` §4 (DDL), §5 (state machine), §6 (luồng).

| | |
|---|---|
| **Phiên bản** | 1.0 — 2026-11-14 |
| **Trạng thái** | Design proposal (chưa implement) |
| **Codebase tham chiếu** | `anhntnse/MovieTicketBackend` @ `50f12c0` — 110 file Java |
| **Tài liệu liên quan** | `CETA_MovieTicket_Spec_v2.md` (audit + backlog) — tài liệu này là tầng **thiết kế** nằm trên backlog đó |
| **Phạm vi** | Một cụm rạp, nhiều phòng chiếu. Không multi-cinema, không POS, không nhượng quyền. |

**Cách đọc:** mỗi phần ứng với một mức trừu tượng. Nếu bạn chỉ có 10 phút, đọc §1, §2 và §4. Nếu bạn sắp code, đọc từ §6 trở đi. Nếu hội đồng hỏi "vì sao", đọc Phụ lục C (ADR).

---

## Mục lục

| § | Nội dung | Mức |
|---|---|---|
| 1 | Bối cảnh & mục tiêu | L0 |
| 2 | Kiến trúc container & topology | L1 |
| 3 | Phân rã module & luật phụ thuộc | L2 |
| 4 | Mô hình domain & bất biến | L2 |
| 5 | Mô hình dữ liệu (DDL) | L3 |
| 6 | Luồng runtime (sequence) | L3 |
| 7 | Concurrency & transaction boundary | L3 |
| 8 | Thiết kế API | L3 |
| 9 | Bảo mật | L3 |
| 10 | Pricing & Notification | L3 |
| 11 | Cross-cutting concerns | L4 |
| 12 | Triển khai & vận hành | L4 |
| 13 | Chiến lược test | L4 |
| 14 | As-is → To-be: bảng chuyển đổi | L4 |
| 15 | Non-goals & lộ trình | — |
| A–D | Phụ lục: package tree, enum, ADR, checklist | — |

---

# §1. Bối cảnh & mục tiêu (L0)

## 1.1 System context

```
        ┌──────────────┐          ┌──────────────┐
        │ Android app  │          │  Web admin   │
        │ (Kotlin)     │          │  (React)     │
        └──────┬───────┘          └──────┬───────┘
               │ REST + STOMP            │ REST
               ▼                         ▼
        ┌─────────────────────────────────────────┐
        │            CETA Backend                 │
        │      (Spring Boot, 1 deployment unit)   │
        └───┬─────────────┬──────────────┬────────┘
            │             │              │
            ▼             ▼              ▼
      ┌──────────┐  ┌──────────┐   ┌─────────────┐
      │  MySQL   │  │  Redis   │   │ VNPay GW    │
      │ (luật)   │  │ (cache)  │   │ (external)  │
      └──────────┘  └──────────┘   └─────────────┘
                                        ▲
                        SMTP (Gmail/SES)│  Counter staff
                                        │  (check-in QR)
```

**Actor và quyền hạn**

| Actor | Kênh | Quyền |
|---|---|---|
| Customer (guest) | Android / Web | Xem phim, suất chiếu, seat map |
| Customer (đã đăng nhập) | Android | Toàn bộ flow đặt vé, xem vé, hủy |
| Counter staff | Web admin | Check-in vé bằng QR / mã vé |
| Admin | Web admin | CRUD phim, phòng, ghế, suất chiếu, báo cáo |
| VNPay | Server-to-server | Gọi IPN |
| Scheduler (nội bộ) | — | Expire booking, retry email, đối soát |

## 1.2 Mục tiêu thiết kế (đo được)

| ID | Mục tiêu | Ngưỡng |
|---|---|---|
| G-1 | Không bao giờ bán trùng một ghế cho hai booking | 0 trường hợp, kể cả khi Redis mất dữ liệu |
| G-2 | Không bao giờ có vé tồn tại cho booking không hợp lệ | `0` kết quả với query kiểm tra |
| G-3 | Payment callback xử lý đúng 1 lần dù nhận N lần | N=5 ⇒ 1 email, 1 transition |
| G-4 | Seat map phản hồi p95 < 150 ms với 1500 ghế | đo bằng k6 |
| G-5 | Tạo booking + build URL VNPay p95 < 200 ms | đo bằng k6 |
| G-6 | Mọi thay đổi trạng thái booking có audit trail | 100% |
| G-7 | Hệ thống chạy được bằng `docker compose up` từ repo trắng | không cần hỏi tác giả |
| G-8 | Chi phí hạ tầng = 0 đồng/tháng | 1 VM free tier |

## 1.3 Ràng buộc

| Loại | Ràng buộc |
|---|---|
| Kỹ thuật | Java 21, Spring Boot 3.x, MySQL 8, Redis 7 — không đổi (yêu cầu môn học + CV) |
| Nhân lực | 1 developer, ~5 tuần, song song làm Android |
| Hạ tầng | 1 VM free tier, RAM 12 GB, không có load balancer |
| Nghiệp vụ | Thanh toán 15 phút, hủy trước 2h, tối đa 8 ghế/booking |
| Tổ chức | Code phải đọc được bởi giảng viên không quen DDD |

---

# §2. Kiến trúc container & topology (L1)

## 2.1 Quyết định: **modular monolith**, không microservices

| Tiêu chí | Modular monolith | Microservices |
|---|---|---|
| Số process phải vận hành | 1 | 4–6 |
| Transaction qua module | Local TX (ACID) | SAGA + outbox |
| Bảo vệ đồ án trước hội đồng | Giải thích 3 phút | Giải thích 30 phút, dễ bị hỏi khó |
| Phù hợp 1 dev / 5 tuần | ✅ | ❌ |
| Đáp ứng rubric | ✅ (không rubric nào yêu cầu microservices) | Thừa |

**Chốt: 1 artifact Spring Boot, phân rã theo module trong code, ranh giới module được enforce bằng package + ArchUnit test.** Đây là cách duy nhất để sau này tách service nếu cần, mà không phải viết lại hôm nay.

## 2.2 Container

| Container | Trách nhiệm | Công nghệ | Stateful? |
|---|---|---|---|
| `api` | Toàn bộ REST + WebSocket + scheduler | Spring Boot 3.4 / Java 21 | Không |
| `mysql` | Nguồn sự thật duy nhất | MySQL 8.4 | ✅ volume |
| `redis` | Cache seat map (tuỳ chọn) + WS fan-out | Redis 7 | Có thể mất |
| `tunnel` | HTTPS ingress | cloudflared | Không |
| `nginx` (optional) | Serve ảnh poster tĩnh | nginx:alpine | ✅ volume |

**Điều quan trọng nhất của §2.2:** `api` là **stateless hoàn toàn**. Không có `ConcurrentHashMap` nào giữ nghiệp vụ trong RAM. Đây là điều kiện để sau này scale, và là điều code hiện tại đang vi phạm (`inMemoryFallback`).

## 2.3 Topology triển khai

```
        Internet
           │
    ┌──────▼───────┐
    │ Cloudflare   │  HTTPS + WSS, free
    │  Tunnel      │
    └──────┬───────┘
           │ HTTP (localhost)
    ┌──────▼───────────────────────────────┐
    │  Oracle Cloud VM — 2 OCPU / 12 GB    │
    │  ┌────────────────────────────────┐  │
    │  │ api  :8080   (JVM -Xmx1g)      │  │
    │  ├────────────────────────────────┤  │
    │  │ mysql :3306  (buffer pool 512M)│  │
    │  ├────────────────────────────────┤  │
    │  │ redis :6379  (maxmemory 256M)  │  │
    │  └────────────────────────────────┘  │
    └──────────────────────────────────────┘
```

**Vì sao 1 instance là đúng, không phải giải pháp tình thế:** với 1 cụm rạp, tải đỉnh ước tính ≤ 60 req/s đọc và ≤ 10 req/s ghi (§12.1). Một JVM 1 GB heap xử lý được 10× con số đó. Thêm instance chỉ mang lại 2 vấn đề mới: WebSocket broker phải relay qua Redis, và scheduler phải có distributed lock. **Không có instance thứ hai = không có hai vấn đề đó.** Ghi rõ trong báo cáo, đó là quyết định kiến trúc, không phải thiếu sót.

## 2.4 Nguyên tắc nhất quán (bất di bất dịch)

| # | Nguyên tắc | Hệ quả nếu vi phạm |
|---|---|---|
| P-1 | MySQL là nguồn sự thật duy nhất của trạng thái ghế/booking | Bán trùng ghế (bug hiện tại) |
| P-2 | Redis chỉ là cache, mất không ảnh hưởng tính đúng | Fallback RAM ⇒ sai dữ liệu |
| P-3 | Mọi chuyển trạng thái booking đi qua 1 cửa | Vé "ma", trạng thái mâu thuẫn |
| P-4 | Mọi tác động ngoài (VNPay, SMTP) đều phải idempotent theo khoá nghiệp vụ | Gửi trùng mail, trừ tiền 2 lần |
| P-5 | API là hợp đồng; Android không bao giờ là nơi giữ luật | Client cũ phá vỡ backend |
| P-6 | Không có business logic trong Controller, không có HTTP trong Domain | Không test được không cần Spring |

---

# §3. Phân rã module & luật phụ thuộc (L2)

## 3.1 Mười module

| Module | Trách nhiệm | Aggregate chính | Ai gọi nó |
|---|---|---|---|
| `identity` | Đăng ký, đăng nhập, JWT, refresh, profile | `User`, `RefreshToken` | tất cả (chỉ qua `CurrentUser`) |
| `catalog` | Phim, thể loại, trạng thái chiếu, ảnh | `Movie`, `Genre` | `scheduling` |
| `facility` | Phòng, ghế vật lý, loại phòng/ghế | `Room`, `Seat` | `scheduling`, `inventory` |
| `scheduling` | Suất chiếu, kiểm tra xung đột lịch | `Showtime` | `inventory`, `booking` |
| `inventory` | **Tồn kho ghế theo suất chiếu** — module lõi | `ShowtimeSeat` | `booking`, `pricing` |
| `pricing` | Tính giá, chính sách giá | `PricingPolicy` | `booking`, `inventory` |
| `booking` | Giữ ghế, checkout, state machine, hủy, expire | `Booking` | `payment`, `ticketing` |
| `payment` | VNPay: tạo URL, callback, IPN, idempotency | `PaymentTransaction` | `booking` (callback) |
| `ticketing` | Sinh vé, QR ký, check-in | `Ticket` | — |
| `notification` | Email, (sau: in-app, FCM) | `EmailLog` | mọi module (qua event) |

## 3.2 Luật phụ thuộc (enforce bằng ArchUnit)

```
        identity ◄──────────────────┐
            ▲                       │
   catalog  │  facility   pricing   │
      ▲     │     ▲         ▲      │
      └── scheduling ─────────┘     │
              ▲                    │
              │                    │
          inventory ◄──────────────┤
              ▲                    │
              │                    │
           booking ────────────────┘
           ▲      ▲
      payment   ticketing
              │
              ▼
        notification  (chỉ nhận event, không ai depend vào)
```

| Luật | Diễn giải |
|---|---|
| R-1 | Module chỉ gọi module **ở tầng dưới hoặc cùng tầng**, không gọi ngược chiều mũi tên |
| R-2 | `booking` **không** được import `payment`; giao tiếp qua `PaymentGateway` interface đặt trong `booking.port` |
| R-3 | `notification` chỉ được gọi qua `ApplicationEventPublisher`, không ai import nó |
| R-4 | Không module nào import `org.springframework.web.*` ngoài package `api` |
| R-5 | `domain` (entity + state machine + value object) không import `Repository`, `RedisTemplate`, `SimpMessagingTemplate` |

ArchUnit test — 6 dòng, đáng giá hơn 20 test controller:

```java
@ArchTest static final ArchRule no_web_in_domain =
    noClasses().that().resideInAPackage("..domain..")
        .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "..repository..");
```

## 3.3 Cấu trúc package trong mỗi module

```
booking/
├── api/
│   ├── BookingController.java
│   ├── dto/                    request/response, có validation annotation
│   └── mapper/                 entity ↔ dto, không đặt logic
├── application/
│   ├── CreateBookingUseCase.java  một class = một use case = một transaction
│   ├── CheckoutUseCase.java
│   ├── CancelBookingUseCase.java
│   ├── ExpireBookingsJob.java  @Scheduled
│   └── BookingQueryService.java  read-only, có thể bỏ qua domain
├── domain/
│   ├── Booking.java            entity + invariant trong chính nó
│   ├── BookingStateMachine.java
│   ├── BookingStatus.java
│   └── SeatSelection.java      value object
└── infra/
    ├── BookingRepository.java
    └── ShowtimeSeatRepository.java
```

**Quy tắc 1 dòng:** `application/` là nơi duy nhất biết "transaction bắt đầu ở đâu"; `domain/` là nơi duy nhất biết "luật là gì"; `api/` không biết gì.

---

# §4. Mô hình domain & bất biến (L2)

## 4.1 Sơ đồ quan hệ (ERD dạng text)

```
Genre 1──n Movie
Movie 1──n Showtime
Room  1──n Seat
Room  1──n Showtime
Showtime 1──n ShowtimeSeat  n──1 Seat          ← SỔ CÁI TỒN KHO
ShowtimeSeat  n──1 Booking   (nullable)
User  1──n Booking 1──n Ticket n──1 Seat
Booking 1──n PaymentTransaction
Booking 1──n BookingStatusHistory
Booking 1──n EmailLog
User  1──n RefreshToken
```

## 4.2 Aggregate root & ranh giới transaction

| Aggregate | Root | Thành viên | Bất biến phải giữ |
|---|---|---|---|
| BookingAggregate | `Booking` | `Ticket`, `PaymentTransaction` (ref) | Không có 2 ticket cùng `(showtime, seat)`; tổng tiền = Σ ticket.price |
| InventoryAggregate | `ShowtimeSeat` (mỗi dòng là 1 root nhỏ) | — | 1 ghế/1 suất chỉ có 1 trạng thái (AVAILABLE/SOLD/BLOCKED); `SOLD` ⇒ có `booking_id`; chỉ được ghi ở IPN |
| FacilityAggregate | `Room` | `Seat` | Không đổi `totalRows/Cols` khi đã có ghế (code hiện tại đã đúng) |
| ScheduleAggregate | `Showtime` | — | Không chồng lấn phòng; `endTime = startTime + movie.duration + cleanup` |
| PaymentAggregate | `PaymentTransaction` | — | `(provider, txn_ref)` unique |

**Vì sao `ShowtimeSeat` là root riêng chứ không nằm trong `Booking`:** nếu để inventory là con của Booking, thì "ghế đã bán" lại phụ thuộc vào Booking — đúng cái vòng lặp logic đã sinh ra F-01 (ghế chết vĩnh viễn). Tách ra thì inventory tự nói được "tôi đang SOLD", độc lập với việc booking sống hay chết.

## 4.3 Mười bất biến (đây là phần đáng chấm điểm nhất)

| ID | Bất biến | Enforce ở đâu |
|---|---|---|
| I-1 | Một ghế trong một suất chiếu chỉ thuộc tối đa một booking `PAID` | `PRIMARY KEY (showtime_id, seat_id)` + `UPDATE ... WHERE status='AVAILABLE'` tại IPN |
| I-2 | `SOLD` ⟺ tồn tại booking `PAID` sở hữu ghế đó | Cùng transaction với claim + transition |
| I-3 | Không tồn tại Ticket cho booking `CANCELLED/EXPIRED` | Ticket sinh **sau** khi PAID; hoặc cascade xoá |
| I-4 | `BookingStatus` chỉ đi theo cạnh hợp lệ của state machine | `BookingStateMachine.transition()` duy nhất |
| I-5 | `totalAmount = Σ ticket.price`, chốt tại F2 và **không** đổi; `ticket.price` là **phân bổ** từ `totalAmount`, không tính lại | Snapshot giá; cấm update; xem rule §10.1 |
| I-6 | Một `(provider, txn_ref)` chỉ xử lý một lần | UNIQUE + `processed_at` |
| I-7 | Mỗi `(booking, template)` chỉ gửi tối đa một email | PK của `email_log` |
| I-8 | Ghế `inactive` không thể AVAILABLE | Check khi bán + khi admin chuyển trạng thái |
| I-9 | Suất chiếu đã `PAID` không được sửa/xoá | Check trong `scheduling` |
| I-10 | Mọi thay đổi trạng thái có 1 dòng history | Listener / cùng transaction |

**Cách kiểm chứng I-1..I-10 trong báo cáo:** mỗi bất biến ứng với 1 SQL assert trong test — chạy sau khi kịch bản concurrency kết thúc. Đây là bằng chứng mạnh hơn "em đã implement đúng".

## 4.4 Value object nên có (nhỏ nhưng đáng)

| VO | Vì sao |
|---|---|
| `Money` | Chặn lỗi làm tròn khi nhân hệ số; VND không có phần lẻ ⇒ luôn `setScale(0, HALF_UP)` ở biên |
| `SeatRef(showtimeId, seatId)` | Khoá tự nhiên, tránh truyền 2 Long rời rạc khắp nơi |
| `TicketCode` | Sinh + verify chữ ký ở một chỗ, không để `Ticket.code` là chuỗi tự do |

---

# §5. Mô hình dữ liệu (L3)

> **Quy ước:** mọi cột thời gian là `DATETIME(3)` lưu **UTC**; hiển thị đổi sang `Asia/Ho_Chi_Minh` ở biên API. Tiền là `DECIMAL(12,0)` cho VND (không phần lẻ), hoặc `DECIMAL(12,2)` nếu sau này bán ngoại tệ.

## 5.1 Bảng mới — `showtime_seat` (sổ cái tồn kho)

```sql
CREATE TABLE showtime_seat (
  showtime_id      BIGINT   NOT NULL,
  seat_id          BIGINT   NOT NULL,
  status           ENUM('AVAILABLE','SOLD','BLOCKED') NOT NULL DEFAULT 'AVAILABLE',
  booking_id       BIGINT   NULL,          -- NULL khi AVAILABLE/BLOCKED
  price_snapshot   DECIMAL(12,2) NULL,     -- giá tại thời điểm bán
  version          BIGINT   NOT NULL DEFAULT 0,
  updated_at       DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
                   ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (showtime_id, seat_id),
  KEY idx_booking (booking_id),
  CONSTRAINT fk_ss_showtime FOREIGN KEY (showtime_id) REFERENCES showtimes(id),
  CONSTRAINT fk_ss_seat     FOREIGN KEY (seat_id)     REFERENCES seats(id)
) ENGINE=InnoDB;
```

**4 trạng thái (baseline 16/11).** `AVAILABLE → HELD → SOLD`, cộng `BLOCKED`. Ghế bị giữ tối đa **10 phút** kể từ lúc tạo đơn (`held_until`, `booking_id`), quá hạn thì sweeper nhả về `AVAILABLE`. Chi tiết + lý do: `CETA_Seat_Hold_Addendum.md`; DDL chốt: `CETA_00_BASELINE_v1.md` §4.

**Vì sao PRIMARY KEY tổng hợp:** khoá tự nhiên `(showtime_id, seat_id)` chính là bất biến I-1. Không ai truy vấn theo `id` tự tăng.

**Vì sao `price_snapshot` ở đây mà không chỉ ở `tickets`:** hiển thị giá trên seat map trước khi bán, và giữ nguyên giá đã cam kết khi policy đổi (I-5).

**Sinh dòng khi nào:** trong transaction tạo `showtime`. 10 phòng × 150 ghế × 5 suất/ngày × 30 ngày ≈ **225.000 dòng** — một câu `INSERT ... SELECT` chạy < 1s.

**Inventory bị ghi ở đúng ba chỗ, cùng một module:** claim lúc tạo đơn (§6.2), promote `HELD → SOLD` lúc IPN (§6.3), release lúc hết hạn/bỏ đi (§6.5). **Không module nào khác** được UPDATE `showtime_seat` ngoài `inventory` — enforce bằng ArchUnit.

## 5.2 Bảng mới — `payment_transaction`

```sql
CREATE TABLE payment_transaction (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  booking_id        BIGINT       NOT NULL,
  provider          VARCHAR(16)  NOT NULL,           -- VNPAY
  txn_ref           VARCHAR(64)  NOT NULL,           -- bookingId_attemptNo
  attempt_no        INT          NOT NULL,
  amount            DECIMAL(12,2) NOT NULL,
  provider_txn_no   VARCHAR(64)  NULL,               -- vnp_TransactionNo
  response_code     VARCHAR(8)   NULL,
  provider_status   VARCHAR(8)   NULL,
  raw_payload       JSON         NULL,
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  processed_at      DATETIME(3)  NULL,               -- NULL = chưa xử lý
  UNIQUE KEY uk_provider_txn (provider, txn_ref),
  KEY idx_booking (booking_id),
  CONSTRAINT fk_pt_booking FOREIGN KEY (booking_id) REFERENCES bookings(id)
) ENGINE=InnoDB;
```

`processed_at IS NULL` chính là cờ idempotency. **Không** dùng `booking.status` làm cờ — vì trạng thái booking có thể bị thay đổi bởi lý do khác (hủy tay), sẽ làm mất khả năng phân biệt "đã xử lý callback" và "đang CANCELLED vì lý do khác".

## 5.3 Bảng mới — `booking_status_history`

```sql
CREATE TABLE booking_status_history (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  booking_id  BIGINT      NOT NULL,
  from_status VARCHAR(20) NULL,
  to_status   VARCHAR(20) NOT NULL,
  event       VARCHAR(20) NOT NULL,      -- CHECKOUT/PAY_SUCCESS/TIMEOUT/CANCEL...
  actor       VARCHAR(64) NULL,          -- email user, 'SYSTEM', 'VNPAY_IPN'
  reason      VARCHAR(255) NULL,
  created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_booking (booking_id, id)
) ENGINE=InnoDB;
```

Dùng cho: audit trail (G-6), debug "vì sao đơn này CANCELLED", và trả lời hội đồng câu "làm sao em biết IPN đã chạy?". Ghi **cùng transaction** với transition.

## 5.4 Bảng mới — `email_log`

```sql
CREATE TABLE email_log (
  booking_id   BIGINT      NOT NULL,
  template     VARCHAR(32) NOT NULL,   -- BOOKING_CONFIRMATION / TICKET_QR / CANCELLED
  recipient    VARCHAR(320) NOT NULL,
  attempts     INT         NOT NULL DEFAULT 0,
  last_error   VARCHAR(512) NULL,
  sent_at      DATETIME(3) NULL,
  created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (booking_id, template)
) ENGINE=InnoDB;
```

## 5.5 Thay đổi trên bảng hiện có

```sql
-- bookings: thêm optimistic lock + cột hết hạn + FK chặt hơn
ALTER TABLE bookings
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN expires_at DATETIME(3) NULL,        -- PENDING hết hạn lúc nào
  ADD KEY idx_pending_expiry (status, expires_at);

-- tickets: bỏ unique constraint sai phạm vi, thay bằng unique theo booking
ALTER TABLE tickets DROP INDEX uk_tickets_showtime_seat;
ALTER TABLE tickets
  ADD UNIQUE KEY uk_tickets_booking_seat (booking_id, seat_id),
  ADD COLUMN checked_in_at DATETIME(3) NULL,
  ADD COLUMN qr_nonce VARCHAR(32) NULL;

-- users: cho đăng nhập mềm
ALTER TABLE users
  ADD COLUMN email_verified_at DATETIME(3) NULL,
  ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE';

-- refresh_tokens: index tra cứu
ALTER TABLE refresh_tokens ADD KEY idx_token (token), ADD KEY idx_user_revoked (user_id, revoked_at);
```

**Vì sao bỏ `uk_tickets_showtime_seat`:** đó là constraint đang gây F-01. Nó cố enforce I-1 nhưng ở **sai bảng** — `tickets` là output của nghiệp vụ, không phải sổ cái. I-1 đã được `showtime_seat.PK` giữ.

**Vì sao thêm `uk_tickets_booking_seat`:** vẫn chặn được 1 ghế 2 vé trong cùng 1 booking (nhập nhằng dữ liệu), nhưng không chặn việc bán lại ghế sau khi booking cũ bị hủy.

## 5.5b Bảng mới — `refund_queue` (bổ sung khi bỏ hold)

```sql
CREATE TABLE refund_queue (
  id             BIGINT PRIMARY KEY AUTO_INCREMENT,
  booking_id     BIGINT      NOT NULL,
  payment_txn_id BIGINT      NOT NULL,
  amount         DECIMAL(12,2) NOT NULL,
  reason         VARCHAR(32) NOT NULL,   -- SEAT_UNAVAILABLE / SHOWTIME_CANCELLED / LATE_PAYMENT / CUSTOMER_REQUEST
  status         VARCHAR(16) NOT NULL DEFAULT 'PENDING',  -- PENDING / DONE / REJECTED
  note           VARCHAR(255) NULL,
  created_at     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  resolved_at    DATETIME(3) NULL,
  UNIQUE KEY uk_refund_booking (booking_id),
  KEY idx_status (status, created_at),
  CONSTRAINT fk_rq_booking FOREIGN KEY (booking_id) REFERENCES bookings(id),
  CONSTRAINT fk_rq_txn     FOREIGN KEY (payment_txn_id) REFERENCES payment_transaction(id)
) ENGINE=InnoDB;
```

Bảng này **bắt buộc** trong mô hình chiếm ghế tại IPN: mỗi dòng là một khách đã mất tiền mà chưa có vé. `UNIQUE (booking_id)` đảm bảo không tạo 2 yêu cầu hoàn cho cùng một booking. Admin phải có màn hình đọc bảng này mỗi ngày — đây là thứ thay thế cho "hold 10 phút" mà mô hình cũ có.

## 5.6 Migration (Flyway)

| Version | Nội dung | Downgrade được? |
|---|---|---|
| `V1__baseline.sql` | Schema hiện tại (generate từ entity, không sửa) | — |
| `V2__showtime_seat.sql` | Tạo bảng + backfill từ `shows × seats_in_room` + đánh dấu SOLD từ ticket của booking PAID | ✅ drop table |
| `V3__payment_and_history.sql` | `payment_transaction`, `booking_status_history`, `email_log` | ✅ |
| `V4__booking_version_expiry.sql` | `version`, `expires_at`, index | ⚠️ cần drop column |
| `V5__ticket_constraint_fix.sql` | Bỏ unique cũ, thêm mới | ✅ |

**Backfill V2 phải idempotent** — chạy lại không nhân đôi dòng:

```sql
INSERT INTO showtime_seat (showtime_id, seat_id, status, booking_id)
SELECT s.id, se.id,
       CASE WHEN EXISTS (SELECT 1 FROM tickets t JOIN bookings b ON b.id=t.booking_id
                         WHERE t.showtime_id=s.id AND t.seat_id=se.id AND b.status='PAID')
            THEN 'SOLD' ELSE 'AVAILABLE' END,
       (SELECT t.booking_id FROM tickets t JOIN bookings b ON b.id=t.booking_id
         WHERE t.showtime_id=s.id AND t.seat_id=se.id AND b.status='PAID' LIMIT 1)
FROM showtimes s
JOIN seats se ON se.room_id = s.room_id
ON DUPLICATE KEY UPDATE status = VALUES(status), booking_id = VALUES(booking_id);
```

## 5.7 Chiến lược index & hiệu năng

| Truy vấn | Index dùng | Ước tính |
|---|---|---|
| Seat map 1 suất chiếu | `showtime_seat` PK (prefix `showtime_id`) | 1500 dòng, < 5 ms |
| Sweeper tìm booking PENDING quá hạn | `idx_pending_expiry (status, expires_at)` | index range scan |
| Booking history của user | `idx_bookings_user` | < 10 ms |
| Tìm booking PENDING quá hạn | `idx_pending_expiry` | < 5 ms |
| Tra payment theo txn_ref | `uk_provider_txn` | unique lookup |

**Giữ nguyên tắc:** không join quá 3 bảng trong 1 API public. Seat map phục vụ từ 1 query duy nhất rồi cache.

---

# §6. Luồng runtime (L3)

Tất cả luồng ghi rõ **điểm thất bại** — vì phần lớn bug hiện tại nằm ở nhánh lỗi, không phải nhánh thành công.

## 6.1 F1 — Xem seat map

```
GET /api/v1/showtimes/{id}/seats
  1. ShowtimeQuery.get(id)                 → 404 nếu không có
  2. Redis GET seat:view:{id}              → hit? trả luôn (TTL 2s)
  3. SELECT * FROM showtime_seat WHERE showtime_id=:id      (1 query, 1500 dòng)
  4. Ghép giá từ price_snapshot (hoặc tính nếu chưa có)
  5. SETEX cache 2s
```
| Nhánh lỗi | Xử lý |
|---|---|
| Suất chiếu đã bắt đầu | Trả `status=CLOSED`, không cho chọn |
| Redis chết | Bỏ qua cache, đọc DB — **vẫn đúng** |
| Ghế `inactive` của phòng | Trả `BLOCKED`, FE không hiển thị là chỗ ngồi |

**Quan trọng:** seat map **không** đọc từ `tickets`. Đây là thay đổi cốt lõi so với code hiện tại.

## 6.2 F2 — Tạo booking: **claim ghế `AVAILABLE → HELD`** + chuyển sang VNPay

```
POST /api/v1/bookings   {showtimeId, seatIds[], paymentProvider}
  [TX begin]
  1. Validate: suất chưa bắt đầu, seatIds ≤ 8, ghế thuộc đúng phòng      → 400
  2. Đọc showtime_seat: nếu có ghế đã SOLD/BLOCKED → 409 SEAT_CONFLICT   (chặn sớm)
  3. pricing.calculate(showtime, seats) → total (snapshot)
  4. INSERT bookings(status=PENDING, total, expires_at = now + 15')
  5. INSERT payment_transaction(txn_ref = "<bookingId>_1", amount, processed_at = NULL)
  6. paymentGateway.buildUrl(txn_ref, amount, ipAddr)
  [TX commit]
  7. KHÔNG ghi showtime_seat · KHÔNG tạo Ticket · KHÔNG gửi email
```

| Nhánh lỗi | Xử lý |
|---|---|
| Ghế đã bán tại bước 2 | 409 kèm `conflicts[]` — khách chọn lại, **chưa mất tiền** |
| Bước 2 pass nhưng ghế bị người khác giữ/mua trước | **Phát hiện ngay tại F2** — claim điều kiện thất bại → `409` kèm `conflicts[]`, khách chọn lại **trước khi** trả tiền |
| VNPay build URL lỗi | Rollback toàn bộ, booking không tồn tại |

**Bước 2 chỉ là tiện ích, không phải bảo đảm.** Nó giảm số ca xung đột, không loại bỏ. Cửa sổ rủi ro = từ bước 6 đến khi callback về (1–3 giây nếu IPN bật, 1–5 phút nếu chỉ có return URL).

### Quyết định L-6 (B) — **ĐÃ BỊ ĐẢO bởi L-8 (16/11): chỉ 1 booking PENDING / user / suất chiếu**

Không thêm ràng buộc "mỗi user chỉ 1 booking PENDING / suất chiếu". Khách bấm 2 lần hoặc retry sẽ tạo 2 booking, cả hai đều có URL VNPay.

| Hệ quả | Ai chịu | Xử lý |
|---|---|---|
| Khách trả tiền cả 2 booking | Booking đến trước thắng (claim được ghế) | Booking thứ hai vào `REFUND_REQUIRED` → hoàn tự động theo quy trình §5.5b |
| Khách chỉ trả 1 | Booking còn lại tự `EXPIRED` sau 15' | Không ảnh hưởng ghế (chưa từng chiếm) |
| Hai user khác nhau, cùng ghế | Người claim sau vào `REFUND_REQUIRED` | Như trên |

**Bắt buộc kèm theo:** cảnh báo mềm ở UI khi user đã có booking PENDING cho cùng suất chiếu ("Bạn đang có một đơn chưa thanh toán") — chỉ là gợi ý, **không** chặn ở backend. Và đếm `paid_without_seat_total` theo từng user để phát hiện hành vi bất thường.

## 6.3 F3 — Payment callback (**IPN + return URL**): `HELD → SOLD` (ghế đã thuộc booking này)

```
POST /api/v1/payments/vnpay/ipn
  -- MỘT use case cho cả hai entrypoint: ApplyPaymentResultUseCase
  --   · IPN (POST) là đường chính, server-to-server
  --   · return URL (GET) gọi CÙNG use case, chỉ khi bật cờ app.payment.return-claim=true
  --   · guard idempotency (bước 3) làm cho việc gọi 2 lần là vô hại
  [TX begin]
  1. verify HMAC-SHA512                        → sai: RspCode 97, không đổi state
  2. parse txn_ref → bookingId, attemptNo
  3. SELECT payment_transaction FOR UPDATE (provider, txn_ref)
       không thấy      → 01
       processed_at≠NULL → 02 "already confirmed" (no-op, không email)
  4. so vnp_Amount/100 == pt.amount             → lệch: 04
     so vnp_TmnCode == config.tmnCode           → sai: 99
  5. nếu PAY_SUCCESS:
       -- CHIẾM GHẾ: lần duy nhất inventory bị ghi trong toàn hệ thống
       -- 5a. KHOÁ + KIỂM TRA TẤT CẢ (all-or-nothing, tránh claim một phần)
       SELECT seat_id FROM showtime_seat
        WHERE showtime_id=:sid AND seat_id IN (:seats) AND status='AVAILABLE'
        ORDER BY seat_id FOR UPDATE
       count != n  → KHÔNG claim ghế nào → nhảy xuống 5c (REFUND_REQUIRED)
       -- 5b. CLAIM (chắc chắn đủ n vì đã khoá ở 5a)
       UPDATE showtime_seat
          SET status='SOLD', booking_id=:bid, version=version+1
        WHERE showtime_id=:sid AND seat_id IN (:seats) AND status='AVAILABLE'
       affected == n → transition(PAY_SUCCESS | LATE_PAYMENT) + sinh Ticket + email + broadcast SOLD
       -- 5c. REFUND_REQUIRED (không có ghế nào bị đổi trạng thái):
                       · KHÔNG sinh Ticket
                       · transition(PAY_FAIL, reason=SEAT_UNAVAILABLE) → CANCELLED
                       · INSERT refund_queue(booking_id, amount, reason)
                       · email xin lỗi NGAY (template RIÊNG, không phải template thất bại)
                       · alert admin
       ⚠️ Nếu claim một phần mà commit, ghế SOLD sẽ trỏ vào booking CANCELLED
          → ghế chết vĩnh viễn và vi phạm I-2. Vì vậy 5a phải khoá TRƯỚC khi UPDATE.
     nếu PAY_FAIL:
       transition(PAY_FAIL)        -- không đụng inventory: chưa từng chiếm ghế
  6. UPDATE payment_transaction SET processed_at=NOW(), provider_status, raw_payload
  7. INSERT email_log(booking_id, template)   ← PK chống trùng
  [TX commit]
  8. AFTER_COMMIT: gửi email async · broadcast WS · alert nếu REFUND_REQUIRED
```

| Nhánh lỗi | Hệ quả |
|---|---|
| Callback đến 2 lần | Lần 2 dừng ở bước 3 → trả 02, **không** email, **không** transition, **không** chiếm ghế lần nữa |
| IPN đến trước return URL | Bình thường; return URL sau đó thấy `processed_at != NULL` |
| Khách tắt app, không quay về | IPN vẫn chiếm ghế + gửi vé. Đây là lý do **bắt buộc** phải có IPN |
| Hai người trả tiền cùng 1 ghế | Người thứ 2 nhận `affected < n` → vào nhánh REFUND_REQUIRED. **Không bao giờ có 2 vé cho 1 ghế** |
| Callback giả mạo | Bước 1 chặn |

### Quy tắc thanh toán muộn (bắt buộc phải định nghĩa)

IPN có thể về **sau** khi `BookingExpiryJob` đã đẩy booking sang `EXPIRED` (khách trả tiền ở phút 14:50, job chạy phút 15:00). Khi đó state machine không có cạnh `EXPIRED → PAY_SUCCESS`, nên nếu không xử lý, ta sẽ claim ghế rồi transition thất bại ⇒ **ghế SOLD mồ côi**.

**Luật chốt:** thêm cạnh `EXPIRED → PAID` với event `LATE_PAYMENT`, **chỉ được dùng khi** claim ở 5b thành công (ghế vẫn còn) — vì khách đã trả tiền và ghế còn trống thì phục vụ tốt hơn hoàn tiền. Nếu claim thất bại ⇒ `REFUND_REQUIRED` như thường.

Hệ quả cần nhớ: `EXPIRED` **không** phải trạng thái cuối; đừng viết `switch` giả định nó không thể chuyển tiếp.

**Vì sao chiếm ghế đặt ở đây chứ không ở F2:** theo quyết định 14/11, ghế chỉ thuộc về khách khi tiền đã vào. Cái giá là nhánh REFUND_REQUIRED — nó phải được xử lý **trong cùng transaction**, không phải một job chạy sau.

## 6.4 F4 — Return URL (entrypoint thứ hai, không phải "chỉ hiển thị")

```
GET /api/v1/payments/vnpay/return
  1. verify HMAC                                → sai: trang lỗi, không đổi state
  2. applyPaymentResult(params)                 ← CÙNG use case với IPN (§6.3)
       · nếu payment_transaction.processed_at != NULL → no-op, chỉ đọc kết quả
       · nếu chưa xử lý → claim ghế + transition + sinh vé như §6.3
  3. đọc booking.status để chọn trang hiển thị
  4. redirect FE: SUCCESS | FAILED | REFUND_PENDING | PROCESSING
```

**Quyết định L-5 (A).** Return URL được phép claim ghế, vì:

| Lý do | Chi tiết |
|---|---|
| Dev local không nhận được IPN | VNPay không gọi được vào `localhost`; nếu chỉ có IPN, giai đoạn phát triển sẽ luôn "thanh toán xong mà không có vé" |
| Khách đóng app trước khi redirect | Không ảnh hưởng — IPN vẫn xử lý server-side. Return URL chỉ là lưới an toàn |
| Gọi 2 lần (IPN + return) | Vô hại: `processed_at` là guard, lần thứ hai là no-op |

**Ranh giới an toàn:** return URL chỉ claim được khi chữ ký hợp lệ (bước 1). Không có chữ ký hợp lệ thì không ai tự tạo được vé bằng cách gọi URL. Cờ `app.payment.return-claim` cho phép tắt ở môi trường chỉ muốn dùng IPN.

Nếu trạng thái còn `PENDING` sau bước 2 (hiếm: VNPay chưa chốt giao dịch), FE hiển thị "đang xác nhận" và poll booking 3 lần × 2s.

## 6.5 F5 — Sweeper (1 job)

| Job | Chu kỳ | Việc |
|---|---|---|
| `BookingExpiryJob` | 60 s | `UPDATE bookings SET status='EXPIRED' WHERE status='PENDING' AND expires_at < NOW()` → lấy danh sách id vừa đổi → ghi history |

**Phải là conditional UPDATE, không phải đọc-rồi-ghi.** Nếu job đọc booking `PENDING` rồi `setStatus(EXPIRED)` và save, nó có thể ghi đè trạng thái `PAID` vừa được IPN đặt trong cùng khoảnh khắc ⇒ mất dấu một booking đã trả tiền. Điều kiện `WHERE status='PENDING'` biến việc này thành vô hại.

**Sweeper 20 giây, và nó PHẢI đụng vào `showtime_seat`.** Booking PENDING quá `hold_expires_at` → nhả ghế (`HELD → AVAILABLE`, xoá `held_until`/`booking_id`) và booking → `EXPIRED`, trong cùng một transaction. Dùng `UPDATE ... WHERE status='PENDING'` / `WHERE status='HELD' AND held_until < NOW()` để chống race với IPN đến muộn.

## 6.6 F6 — Check-in vé

```
POST /api/v1/tickets/check-in  {code}    (role STAFF/ADMIN)
  1. Verify chữ ký HMAC của code (nếu QR) → sai: 400
  2. SELECT ticket JOIN booking → booking PAID?             → 409 nếu không
  3. showtime.startTime - now ≤ 60m?                        → 409 nếu quá sớm
  4. showtime.startTime > now?                              → 409 nếu đã bắt đầu (BR-5)
  5. UPDATE tickets SET checked_in_at=NOW() WHERE id=:id AND checked_in_at IS NULL
       affected=0 → 409 "vé đã dùng"                        ← idempotent bằng WHERE
```
Idempotency **không** cần bảng riêng: `checked_in_at IS NULL` trong `WHERE` chính là guard.

## 6.7 F7 — Hủy booking

```
POST /api/v1/bookings/{id}/cancel     (chủ sở hữu, BR-3: trước ≥ 2h)
  [TX]
  1. ownership check                                       → 403
  2. startTime - now ≥ 2h?                                 → 409
  3. stateMachine.transition(CANCEL)
  4. ticket: checked_in_at IS NULL? nếu đã dùng → 409
  5. inventory: UPDATE showtime_seat SET status='AVAILABLE', booking_id=NULL
     WHERE booking_id=:id AND status='SOLD'      (chỉ dòng của booking này)
  6. history + (nếu đã PAID) tạo yêu cầu hoàn tiền thủ công, KHÔNG tự động refund
  [TX commit]
  7. email CANCELLED + broadcast AVAILABLE
```

---

# §7. Concurrency & transaction boundary (L3)

## 7.1 Giao thức chiếm ghế — **một tầng, một câu UPDATE**

| Tầng | Công cụ | Trả lời câu hỏi | Nếu hỏng |
|---|---|---|---|
| (đã bỏ) | ~~Redis Lua + TTL~~ | — | — |
| **Claim (luật)** | `UPDATE ... WHERE status='AVAILABLE'` trên `showtime_seat` | "Ghế này đã thuộc booking nào" | Không thể hỏng — PK + điều kiện trong WHERE |

**Vì sao BẬT LẠI tầng hold (16/11):** bỏ hold đẩy rủi ro sang khách — họ bị trừ tiền rồi mới biết mất ghế, sinh hoàn tiền + CSKH + review xấu. Hold đẩy rủi ro sang "phải chọn ghế khác", nhẹ hơn và chặn được bằng TTL ngắn + nhả khi bỏ đi + 1 đơn/người/suất. Khác biệt cốt lõi so với code cũ: hold **không** dùng Redis lock, **không** dùng bảng `seat_hold` riêng — nó là 2 cột (`held_until`, `booking_id`) trên chính bảng `showtime_seat`, claim bằng `SELECT ... FOR UPDATE` trên dòng `showtime`. Số liệu thị trường và bài toán dung lượng: `CETA_Seat_Hold_Addendum.md` §1–§2.

**Vì sao không dùng distributed lock (Redisson):** lock chỉ bảo vệ trong lúc giữ lock; khi hết lock dữ liệu vẫn phải đúng, và cái làm nó đúng là `WHERE` + `version`. Ở tải ~1 IPN/giây, tranh chấp gần như bằng 0. Thêm Redisson = thêm 1 phụ thuộc + thêm 1 đường deadlock.

**Rủi ro còn lại, phải theo dõi:** hai khách cùng trả tiền cho một ghế. Không chặn được ở tầng DB — chỉ giảm được bằng IPN (cửa sổ 1–3 giây) và xử lý bằng nhánh REFUND_REQUIRED. Metric: `paid_without_seat_total`, ngưỡng **0**.

## 7.2 Thứ tự khoá (chống deadlock)

Khi một transaction chạm nhiều dòng `showtime_seat`, **luôn sắp xếp `seat_id` tăng dần** trước khi UPDATE. MySQL InnoDB khoá theo thứ tự index; nếu 2 transaction cùng tập ghế nhưng thứ tự khác nhau sẽ deadlock. Một dòng `Collections.sort(seatIds)` là đủ.

Thứ tự tài nguyên toàn cục: `showtime_seat` → `bookings` → `payment_transaction`. Không bao giờ đi ngược.

## 7.3 Ranh giới transaction — bảng quy chuẩn

| Việc | Trong TX? | Lý do |
|---|---|---|
| UPDATE inventory | ✅ | Cùng tính đúng với booking |
| INSERT booking/ticket/history | ✅ | Nguyên tử |
| Gọi VNPay build URL | ✅ (nhưng thuần tính toán, không I/O) | Không có network call |
| Gửi email | ❌ AFTER_COMMIT | SMTP chậm, không được rollback booking |
| Gửi SMS/push | ❌ | Như trên |
| Broadcast WS | ❌ sau commit | Client không được thấy trạng thái chưa commit |
| Gọi API VNPay verify chủ động | ❌ (chỉ trong IPN, best-effort) | Timeout mạng |
| Đọc seat map | ❌ readOnly | Không cần TX ghi |

**Lỗi phổ biến cần tránh:** `@Async` gọi **bên trong** method `@Transactional` — thread async có thể chạy trước commit và đọc dữ liệu cũ. Luôn dùng `@TransactionalEventListener(phase = AFTER_COMMIT)`.

## 7.4 Idempotency ở mọi biên

| Biên | Khoá | Cơ chế |
|---|---|---|
| IPN/return | `(provider, txn_ref)` | unique + `processed_at` |
| Sinh ticket | `(booking_id, seat_id)` | unique |
| Gửi email | `(booking_id, template)` | PK |
| Check-in | `ticket_id` | `WHERE checked_in_at IS NULL` |
| API client retry (Android) | header `Idempotency-Key` | bảng `idempotency_key` (tuỳ chọn, P2) |

## 7.5 Tải và giới hạn thực tế

| Thao tác | Ước tính tải đỉnh | Chi phí DB |
|---|---|---|
| Seat map | 20 req/s (poll 5s × 100 user) | 1 query 1500 dòng, cache 2s ⇒ ~10 query/s |
| Checkout | 1 req/s | ~10 INSERT |
| Callback | < 1 req/s | ~6 UPDATE/INSERT |

Kết luận: **không cần sharding, không cần read replica, không cần cache phức tạp.** Bất kỳ phức tạp nào thêm vào đều là over-engineering — nói rõ điều này khi bảo vệ.

---

# §8. Thiết kế API (L3)

## 8.1 Quy ước

| Mục | Quy ước |
|---|---|
| Versioning | prefix `/api/v1` (hiện không có version — sẽ đau khi Android đã release) |
| Định danh | `Long` id, không lộ `id` tuần tự ở chỗ nhạy cảm (dùng `code`) |
| Thời gian | ISO-8601 UTC (`2026-11-14T05:00:00Z`), client tự đổi múi giờ |
| Tiền | `BigDecimal` dạng số + `currency:"VND"` |
| Phân trang | `?page=0&size=20&sort=startTime,asc` → `{content, page, size, totalElements, totalPages}` |
| Lỗi | RFC 7807 `application/problem+json` |
| Idempotency | header `Idempotency-Key` cho POST tạo booking |
| Correlation | header `X-Request-Id` echo lại + log MDC |

## 8.2 Error model (thay `ResponseStatusException` rải rác)

```json
{
  "type": "https://ceta.vn/errors/seat-conflict",
  "title": "Seat not available",
  "status": 409,
  "detail": "Ghế F7 đã được người khác giữ",
  "instance": "/api/v1/bookings",
  "code": "SEAT_CONFLICT",
  "conflicts": [{"seatId": 45, "label": "F7", "status": "SOLD"}],
  "traceId": "b3f1c2"
}
```

**Vì sao cần `conflicts[]`:** Android cần biết **ghế nào** vướng để bỏ chọn đúng ghế đó. Trả về 1 câu message chung là lý do FE phải reload cả seat map — chậm và tệ UX.

Bảng mã lỗi nghiệp vụ (enum, không dùng string rải rác):

| Code | HTTP | Khi nào |
|---|---|---|
| `SEAT_CONFLICT` | 409 | Ghế đã SOLD/BLOCKED khi tạo booking (kèm `conflicts[]`) |
| `SEAT_UNAVAILABLE` | 409 | Ghế đã bán trong lúc khách thanh toán (hiển thị ở return URL) |
| `SHOWTIME_CLOSED` | 409 | Suất đã bắt đầu |
| `BOOKING_NOT_CANCELLABLE` | 409 | Quá hạn hủy |
| `PAYMENT_ALREADY_PROCESSED` | 200 | IPN lặp (không phải lỗi) |
| `TICKET_ALREADY_USED` | 409 | Check-in 2 lần |
| `MAX_SEATS_EXCEEDED` | 400 | > 8 ghế |

## 8.3 Auth matrix

| Endpoint | Guest | USER | STAFF | ADMIN |
|---|---|---|---|---|
| `GET /movies`, `/showtimes`, `/seats` | ✅ | ✅ | ✅ | ✅ |
| `POST /auth/register|login|refresh` | ✅ | | | |
| `POST /bookings` | | ✅ | | |
| `GET /bookings`, `/bookings/{id}` | | ✅ own | | ✅ |
| `POST /bookings/{id}/cancel` | | ✅ own | | ✅ |
| `GET /tickets`, `/tickets/{id}` | | ✅ own | | ✅ |
| `POST /tickets/check-in` | | | ✅ | ✅ |
| `POST /payments/vnpay/ipn` | ✅ (signature là auth) | | | |
| `/admin/**` | | | | ✅ |
| `/actuator/health` | ✅ | | | |

**Sửa lỗi thiết kế hiện tại:** `.requestMatchers("/api/**").hasRole("ADMIN")` là fallback ⇒ mọi endpoint mới vô tình thành ADMIN-only. Đổi thành `.anyRequest().authenticated()` + danh sách PUBLIC tường minh, để lỗi mặc định là **an toàn hơn** (401) chứ không phải "vô tình mở".

## 8.4 Endpoint inventory (đầy đủ, theo module)

| Module | Method + Path | Ghi chú |
|---|---|---|
| identity | `POST /auth/register` | gửi mail verify |
| | `POST /auth/login` | rate-limit 5/phút/IP |
| | `POST /auth/refresh` | rotation ✅ đã đúng |
| | `POST /auth/logout` | revoke |
| | `GET /auth/me` · `PATCH /me` · `PUT /me/password` | **mới** |
| | `POST /auth/forgot-password` · `POST /auth/reset-password` | **mới** |
| | `POST /auth/verify-email` · `POST /auth/resend-verification` | **mới** |
| catalog | `GET /movies?q=&genre=&status=&page=` | **mới**: search/filter/page |
| | `GET /movies/{id}` · `GET /genres` | |
| | `POST/PUT/DELETE /admin/movies` | |
| facility | `GET /rooms/{id}/seats` | admin layout |
| | `POST/PUT /admin/rooms` | sửa bug `roomType` |
| scheduling | `GET /showtimes?movieId=&date=` | **mới** filter |
| | `GET /showtimes/{id}/seats` | seat map (F1) |
| | `POST/PUT/DELETE /admin/showtimes` | chặn sửa khi có vé |
| | `POST /bookings` | tạo booking + URL VNPay (F2) |
| | `GET /bookings` · `GET /bookings/{id}` | **mới**: detail |
| | `POST /bookings/{id}/cancel` | **mới** |
| payment | `POST /payments/vnpay/ipn` | **mới** (bắt buộc) |
| | `GET /payments/vnpay/return` | redirect |
| ticketing | `GET /tickets` · `GET /tickets/{id}` | **mới** |
| | `GET /tickets/{id}/qr` | PNG QR |
| | `POST /tickets/check-in` | staff |
| notification | `GET /notifications` · `PATCH /notifications/{id}/read` | P2 |
| report | `GET /admin/reports/revenue?from=&to=` | P3 |

## 8.5 Hợp đồng WebSocket

```json
// SUBSCRIBE /topic/showtime/{id}/seats
// CONNECT header: Authorization: Bearer <accessToken>   ← bắt buộc
{
  "seq": 148,
  "showtimeId": 12,
  "at": "2026-11-14T06:12:03.412Z",
  "seats": [
    {"seatId": 45, "label": "F7", "status": "SOLD"},
    {"seatId": 46, "label": "F8", "status": "AVAILABLE"}
  ]
}
```

| Quy tắc | Lý do |
|---|---|
| `seq` đơn điệu / suất chiếu | Client bỏ event cũ; SockJS hay gửi lệch thứ tự sau reconnect |
| Không gửi `userId` của người khác | Rò rỉ thông tin — chỉ gửi trạng thái |
| Event chỉ là *gợi ý*, client vẫn phải có API refresh | WS không đảm bảo delivery |
| Từ chối CONNECT không token | 1 `ChannelInterceptor`, không phải per-message check |

---

# §9. Bảo mật (L3)

| Hạng mục | Hiện tại | Thiết kế |
|---|---|---|
| Access token | JWT HS256, TTL ngắn | Giữ; `sub=userId`, `roles[]`, TTL 15' |
| Refresh token | Random, revoke được ✅ | Giữ; thêm phát hiện reuse (nếu token đã revoked mà bị dùng → revoke toàn bộ family) |
| Password | BCrypt ✅ | Giữ; thêm policy ≥ 8 ký tự + kiểm tra trong blacklist phổ biến |
| Brute force | Không có | Bucket4j in-memory (1 instance) — 5 login/phút/IP, 20/phút/user |
| CORS | `WebConfig` cần review | Allowlist origin từ config, không `*` |
| WS auth | **Không có** | Interceptor verify JWT ở CONNECT |
| QR vé | Chuỗi base64 tự do | `HMAC-SHA256(ticketId|exp)` + `exp` ≤ startTime + 2h; verify không cần DB |
| Secrets | properties | env + `.env.example`; fail-fast nếu thiếu |
| Log PII | Log toàn bộ VNPay params ở INFO | Mask `vnp_Amount`, bỏ raw hash-data; log `txn_ref` + kết quả |
| SQL injection | JPA | Giữ; cấm native query ghép chuỗi |
| Mass assignment | DTO tách entity ✅ | Giữ |
| Rate limit seat map | Không có | Cache 2s đã đủ giảm tải; thêm limit 30 req/s/IP nếu mở public |

**Điểm cần nói rõ với hội đồng:** hệ thống chạy HTTP sau Cloudflare Tunnel nên TLS do Cloudflare lo; phía app **không** tự terminate TLS. Nếu chấm điểm yêu cầu HTTPS end-to-end thì bật nginx + Let's Encrypt trên VM.

---

# §10. Pricing & Notification (L3)

## 10.1 Pricing — rule engine nhỏ, dữ liệu hoá

```java
public interface PriceRule { boolean applies(PriceContext c); Money apply(Money p, PriceContext c); }

// PriceContext(showtime, seat, room, dayOfWeek, timeOfDay, policySet)
// Thứ tự cố định:  base → roomMultiplier → seatSurcharge → timeSurcharge → cap
```

`pricing_policy` là bảng dữ liệu, không phải `switch` trong code:

| room_type | seat_type | day_of_week | multiplier | surcharge | active |
|---|---|---|---|---|---|
| IMAX | NULL | NULL | 1.50 | 0 | true |
| FOUR_DX | NULL | NULL | 1.80 | 0 | true |
| ANY | VIP | NULL | 1.00 | 20000 | true |
| ANY | NULL | SAT,SUN | 1.15 | 0 | true |

| Quy tắc | Chi tiết |
|---|---|
| Thứ tự áp dụng | Room → Seat → Time. Đổi thứ tự = đổi giá ⇒ **phải cố định và có test** |
| Làm tròn | Cuối cùng, `setScale(0, HALF_UP)` cho VND |
| Snapshot | Ghi vào `showtime_seat.price_snapshot` và `tickets.price` (I-5) |
| Cache | Policy load 1 lần / 60s (`@Cacheable`), invalidate khi admin sửa |
| Fallback | Không có policy khớp ⇒ `showtime.basePrice` (không bao giờ 500) |

**Rule cứng (L-7): giá vé KHÔNG được tính lại lúc chiếm ghế.**

`booking.totalAmount` chốt tại F2 và là số tiền VNPay thu. Khi claim thành công ở §6.3, `ticket.price` được **phân bổ** từ `totalAmount` đã chốt, tuyệt đối không gọi lại `pricingService.calculate()` — vì policy có thể đã đổi giữa F2 và IPN (admin sửa giá, qua nửa đêm đổi phụ thu cuối tuần).

Cách phân bổ: `ticket.price = priceSnapshot(seat tại F2)` đã lưu khi tạo booking; nếu chưa lưu thì chia đều `totalAmount / n` với phần dư cộng vào ghế đầu tiên. Luôn `setScale(0, HALF_UP)` và assert `Σ ticket.price == totalAmount` trước khi commit.

## 10.2 Notification

| Việc | Thiết kế |
|---|---|
| Kênh | Email (bắt buộc), in-app (P2), FCM (P3) |
| Kích hoạt | `@TransactionalEventListener(AFTER_COMMIT)` — không gửi trước commit |
| Chống trùng | `email_log` PK `(booking_id, template)` |
| Retry | `@Scheduled` mỗi 5' quét `sent_at IS NULL AND attempts < 5`, backoff theo attempts |
| Nội dung | Template Thymeleaf (đã có `spring-boot-starter-mail`) |
| Lỗi SMTP | Không ảnh hưởng booking (đã đúng trong code hiện tại) |
| QR trong mail | PNG inline, sinh từ `ticketing` |

---

# §11. Cross-cutting (L4)

| Concern | Thiết kế |
|---|---|
| **Config** | `application.properties` (base) + `-dev` + `-prod`; mọi secret qua env; `@ConfigurationProperties` có `@Validated` ⇒ fail-fast lúc boot |
| **Error handling** | `@RestControllerAdvice` map exception → ProblemDetail; domain ném `DomainException(code)`; không ném `ResponseStatusException` trong domain |
| **Validation** | Bean Validation ở DTO; domain tự validate invariant (không tin DTO) |
| **Logging** | SLF4J + MDC `traceId`, `userId`, `bookingId`; JSON layout ở prod; **không** log payload thanh toán |
| **Audit** | `booking_status_history` + `created_by` ở entity admin |
| **Metrics** | Micrometer: `booking_created_total{status}`, `paid_without_seat_total`, `payment_callback_total{result}`, `seatmap_duration` |
| **Health** | `/actuator/health` (liveness/readiness), `/actuator/info` version + commit |
| **Time** | `Clock` bean inject được (test được), không `Instant.now()` rải rác |
| **Scheduling** | `@Scheduled` với `fixedDelay`; guard multi-instance bằng `pg_advisory_lock` tương đương MySQL `GET_LOCK` (chỉ cần nếu scale >1) |
| **Cache** | Redis 2 tầng: `seat:view:{id}` (2s), `pricing:policies` (60s). Key có version prefix để dễ flush |
| **API docs** | springdoc (đã có) + `@Operation`; expose chỉ ở dev/prod-internal |

**Chống N+1 — quy tắc cụ thể:** mọi API list phải dùng `@EntityGraph` hoặc projection DTO; cấm `LAZY` rồi vòng lặp. `open-in-view=false` để lỗi lộ ra ở dev thay vì ở prod.

---

# §12. Triển khai & vận hành (L4)

## 12.1 Ước lượng tài nguyên

| Thành phần | Cấu hình | Ước tính dùng |
|---|---|---|
| JVM `api` | `-Xmx1g -XX:MaxRAMPercentage=60` | 600–800 MB |
| MySQL 8 | `innodb_buffer_pool_size=512M` | 900 MB |
| Redis 7 | `maxmemory 256mb`, `allkeys-lru` | 120 MB |
| OS + docker | — | 800 MB |
| **Tổng** | VM 12 GB | **~2.6 GB** ⇒ dư nhiều (đủ chỗ cho build Maven trên VM nếu cần) |

## 12.2 Môi trường

| Profile | DB | Redis | Mail | Swagger | DDL |
|---|---|---|---|---|---|
| `dev` | MySQL docker local | docker local | MailHog/noop | ✅ | Flyway + `validate` |
| `test` | H2 **chỉ trong test scope** | embedded/`@Mock` | Noop | ❌ | `create-drop` |
| `prod` | MySQL VM | Redis VM | SMTP thật | ❌ (hoặc bảo vệ) | Flyway `validate` |

## 12.3 Pipeline CI

```yaml
# .github/workflows/ci.yml — 3 job, tổng < 5 phút
build:      mvn -B -q verify        # unit + ArchUnit
integration: mvn -B verify -Pit     # Testcontainers: MySQL + Redis
image:      docker buildx build --push   # chỉ khi tag
```
Deploy: 1 job SSH `docker compose pull && docker compose up -d`, có `health` check trước khi báo thành công.

## 12.4 Vận hành tối thiểu

| Việc | Công cụ | Tần suất |
|---|---|---|
| Uptime | UptimeRobot → `/actuator/health` | 5 phút |
| Backup DB | `mysqldump` cron → object storage free | hằng ngày, giữ 7 bản |
| Log | `docker logs` + rotation; (P3) Loki free | — |
| Alert | UptimeRobot email | — |
| Runbook | 1 trang: service chết, DB chết, Redis chết, VNPay timeout | — |

**Kịch bản sự cố phải có câu trả lời viết sẵn:**

| Sự cố | Hệ thống cư xử thế nào | Khôi phục |
|---|---|---|
| Redis chết | Chỉ seat map chậm hơn (đọc DB); tạo booking và thanh toán **không** ảnh hưởng | Khởi động lại; không mất gì vất ⇒ user chọn lại |
| MySQL chết | Toàn bộ 503; WS ngắt | Restore từ backup |
| Scheduler chết | Vẫn bán đúng (điều kiện trong WHERE); chỉ chậm broadcast | Khởi động lại |
| VNPay timeout | Booking PENDING tự EXPIRED sau 15' | User đặt lại |

---

# §13. Chiến lược test (L4)

| Tầng | Số lượng mục tiêu | Ví dụ |
|---|---|---|
| Unit (domain, không Spring) | 25 | State machine: mọi cặp (status, event) hợp lệ/không hợp lệ; PriceRule |
| Slice (`@WebMvcTest`) | 15 | Auth matrix: 403 cho USER trên `/admin/**` |
| Integration (Testcontainers) | 15 | Repositories, Flyway chạy được trên MySQL thật |
| **Concurrency** | 3 | Tranh ghế tại claim, replay IPN, booking hết hạn |
| ArchUnit | 5 | R-1..R-5 |
| Contract (P2) | 5 | Schema response khớp Android model |

**Ba test concurrency — phải có, đây là bằng chứng cho G-1..G-3:**

```java
@Test void onlyOneUserCanBookSameSeat() {           // 20 IPN đồng thời, 1 ghế
    // assert: 1 booking PAID, 19 booking vào REFUND_REQUIRED
    // assert: SELECT count(*) FROM showtime_seat WHERE status='SOLD' = 1
    // assert: count(*) FROM tickets = số ghế của booking thắng (không có vé mồ côi)
}

@Test void ipnIsProcessedExactlyOnce() {            // gọi 5 lần
    // assert: payment_transaction.processed_at không đổi
    // assert: email_log có đúng 1 dòng
    // assert: history có đúng 1 dòng PAY_SUCCESS
}

@Test void expiredBookingIsClosed() {                // không chờ 15 phút
    // dùng Clock bean, set booking.expires_at về quá khứ
    // assert: booking → EXPIRED; inventory KHÔNG đổi (chưa từng chiếm ghế)
}
```

**Không viết:** test getter/setter, test `@Data` Lombok, test repository CRUD do Spring sinh. 25 test controller hiện tại có giá trị thấp hơn 3 test trên.

---

# §14. As-is → To-be: bảng chuyển đổi

| # | Hiện tại | Thiết kế mới | Loại thay đổi |
|---|---|---|---|
| 1 | `tickets` là sổ cái "đã bán" | `showtime_seat` là sổ cái | **Schema** |
| 2 | `holdSeats` = hasKey + set (2 vòng), có khái niệm giữ ghế | Bỏ hẳn: ghế chỉ chiếm tại IPN bằng 1 câu `UPDATE` | **Xoá code** |
| 3 | Checkout dựa vào hold Redis để biết ghế còn trống | Không kiểm hold; IPN claim atomic + nhánh REFUND_REQUIRED | **Luồng** |
| 4 | Ticket tạo ở checkout | Ticket chỉ tạo khi IPN chiếm ghế thành công | **Luồng** |
| 5 | Callback tự đổi status | `BookingStateMachine` + history | **Kiến trúc** |
| 6 | Không có IPN | IPN + return dùng chung use case | Feature |
| 7 | Không idempotency | `payment_transaction.processed_at` | Schema + logic |
| 8 | Không amount check | So `vnp_Amount/100` | Logic |
| 9 | Booking PENDING vĩnh viễn | `expires_at` + `BookingExpiryJob` | Feature |
| 10 | `inMemoryFallback` | Xoá; Redis chết → 503 | **Xoá code** |
| 11 | `SeatHold` entity + cleanup job | Xoá cả hai (không còn khái niệm giữ ghế) | **Xoá code** |
| 12 | `KEYS` pattern | Hash per showtime + `HGETALL` | Hiệu năng |
| 13 | WS không auth | `ChannelInterceptor` JWT | Bảo mật |
| 14 | `/api/**` → ADMIN | `authenticated()` + PUBLIC list | Bảo mật |
| 15 | `roomType` không update | Set + validate | Bug |
| 16 | Pricing `switch` | `pricing_policy` + rule chain | Kiến trúc |
| 17 | `ddl-auto` | Flyway `validate` | Vận hành |
| 18 | `h2` compile scope | `test` scope | Bug |
| 19 | Không có `application.properties` | Base + profile + env | Vận hành |
| 20 | `ResponseStatusException` rải rác | `DomainException` + ProblemDetail | API |

---

# §15. Non-goals & lộ trình

## 15.1 Không làm (và lý do — nói được khi bảo vệ)

| Không làm | Vì sao |
|---|---|
| Microservices | 1 dev; transaction xuyên module thành SAGA — đổi 1 bug lấy 5 bug |
| Event sourcing | Không ai chấm; phải viết projection + replay |
| CQRS tách DB | Tải 60 req/s; thêm đồng bộ 2 store = thêm 1 nguồn lệch |
| Kubernetes | 1 VM, 1 service |
| Multi-tenant / multi-cinema | Phạm vi là 1 cụm rạp |
| Payment gateway thứ 2 (MoMo) | Trừu tượng `PaymentGateway` đã sẵn, implement khi cần |
| GraphQL | Android chỉ cần 20 endpoint |
| Distributed lock (Redisson) | Không giải quyết vấn đề nào mà `WHERE` + unique chưa giải |

## 15.2 Lộ trình 6 sprint

| Sprint | Deliverable | Định nghĩa hoàn thành |
|---|---|---|
| **S1** | Nền: Flyway, `showtime_seat` + backfill, config, Dockerfile/compose, ArchUnit | `docker compose up` từ repo trắng chạy được; CI xanh |
| **S2** | Inventory: claim atomic tại IPN + nhánh REFUND_REQUIRED + booking expiry | 3 test concurrency xanh |
| **S3** | Booking: state machine, history, checkout mới (không tạo ticket), expire job | Replay test xanh; không còn đường tạo 2 vé |
| **S4** | Payment: `payment_transaction`, IPN, amount/tmn check, idempotency, ticket sinh sau PAID | IPN 5 lần = 1 xử lý |
| **S5** | Ticketing + notification: QR ký, check-in, email_log, WS auth + seq | Check-in 2 lần → 1 thành công |
| **S6** | API hoàn thiện: search/filter/paging, booking detail, cancel, profile, admin report; OpenAPI | Android tích hợp không cần hỏi backend |

---

# Phụ lục A — Package tree đầy đủ

```
com.movie.ticket_booking
├── TicketBookingApplication.java
├── common/            ApiException, ErrorCode, ProblemDetailMapper, ClockConfig, Money, PageResponse
├── identity/          api | application | domain(User,RefreshToken,PasswordPolicy) | infra
├── catalog/           api | application | domain(Movie,Genre,MovieStatus) | infra
├── facility/          api | application | domain(Room,Seat,RoomType,SeatType) | infra
├── scheduling/        api | application | domain(Showtime,ScheduleConflictChecker) | infra
├── inventory/         api | application(ClaimSeatsUseCase,SeatMapQuery) | domain(ShowtimeSeat,SeatState) | infra
├── pricing/           application(PriceEngine,PriceRule*) | domain(PricingPolicy,Money) | infra
├── booking/           api | application(CreateBooking,PayCallback,Cancel,Expire,Query) | domain(Booking,StateMachine,SeatSelection) | infra | port(PaymentGateway)
├── payment/           api(VnPayCallback, Ipn) | application(PaymentCallbackUseCase) | domain(PaymentTransaction) | infra(VnPayGateway, HmacSigner)
├── ticketing/         api | application(QrService,CheckInUseCase) | domain(Ticket,TicketCode) | infra
├── notification/      application(EmailDispatcher) | domain(EmailLog) | infra(SmtpSender,NoopSender)
├── config/            SecurityConfig, WebSocketConfig, RedisConfig, OpenApiConfig, JacksonConfig
└── web/               GlobalExceptionHandler, CorrelationIdFilter, ApiLoggingFilter
```

# Phụ lục B — Enum catalog

| Enum | Giá trị | Ghi chú |
|---|---|---|
| `BookingStatus` | PENDING, PAID, CANCELLED, EXPIRED, REFUNDED | **bỏ `HOLDING`** — không còn khái niệm giữ ghế trước thanh toán |
| `BookingEvent` | CHECKOUT, PAY_SUCCESS, PAY_FAIL, TIMEOUT, CANCEL, REFUND | mới |
| `SeatState` | AVAILABLE, SOLD, BLOCKED | mới |
| `PaymentStatus` | PENDING, SUCCESS, FAILED, CANCELLED | suy ra từ VNPay response code |
| `EmailTemplate` | BOOKING_CONFIRMATION, TICKETS, CANCELLED, VERIFY_EMAIL, RESET_PASSWORD | |
| `RoleName` | USER, STAFF, ADMIN | thêm STAFF |

# Phụ lục C — Architecture Decision Records

| ADR | Quyết định | Phương án bị loại | Hệ quả |
|---|---|---|---|
| ADR-001 | Modular monolith, 1 deployable | Microservices | Không cần SAGA; phải giữ kỷ luật package |
| ADR-002 | DB là nguồn sự thật của tồn kho ghế **và của hold** | Redis là lock chính / bảng `seat_hold` riêng | Hold = 2 cột `held_until`+`booking_id` trên `showtime_seat`; claim bằng `FOR UPDATE` trên dòng `showtime`; Redis chỉ cache đọc 3–5s |
| ADR-003 | `showtime_seat` PK tổng hợp + conditional UPDATE tại IPN | Giữ ghế 2 tầng; optimistic lock đơn thuần | Không deadlock; nhận lại rủi ro REFUND_REQUIRED |
| ADR-004 | Ghế chiếm tại IPN, Ticket sinh ngay sau khi claim thành công | Giữ ghế trước khi thanh toán | Mất bảo vệ trước khi trả tiền; đổi lại bỏ 1 trạng thái + 1 job + 1 tầng |
| ADR-005 | State machine tập trung + bảng history | `setStatus()` ở từng service | Thêm 1 bảng; audit miễn phí |
| ADR-006 | IPN + return dùng chung 1 use case | 2 handler riêng | Một nơi để idempotent |
| ADR-007 | `email_log` PK chống trùng, chưa dùng outbox | Outbox đầy đủ | Đơn giản; chấp nhận mất mail nếu crash (retry job bù) |
| ADR-008 | 1 instance, không ALB | ALB + 2 instance | Không cần relay WS; ghi rõ lý do |
| ADR-009 | Flyway + `ddl-auto=validate` | `ddl-auto=update` | Phải viết migration tay; đổi lại schema có lịch sử |
| ADR-010 | Bỏ AWS khỏi scope đồ án | S3/SQS/SES/EC2 | 0đ/tháng; mất 1 dòng CV (nhưng trung thực) |

# Phụ lục D — Definition of Done cho backend

- [ ] `docker compose up` từ repo trắng ⇒ app healthy, có seed data
- [ ] Flyway chạy từ DB rỗng đến latest, không cần thao tác tay
- [ ] 3 test concurrency xanh trên CI
- [ ] ArchUnit 5 luật xanh
- [ ] Không còn `inMemoryFallback`, `SeatHold`, `redisTemplate.keys`, `hasKey`, `held_by`
- [ ] `HELD` là trạng thái **hợp lệ** (baseline 16/11) — chỉ được ghi bởi module `inventory`
- [ ] Không còn `ticketRepository.findSeatIdsByShowtimeId` trong luồng seat map
- [ ] Mọi endpoint có trong OpenAPI, có auth matrix khớp
- [ ] `/actuator/health` xanh, log có `traceId`
- [ ] Backup + restore đã thử ít nhất 1 lần
- [ ] README: setup 5 lệnh, kiến trúc 1 hình, 3 quyết định lớn + lý do

---

*Hết. Tài liệu này mô tả thiết kế **đề xuất** — đối chiếu với code hiện tại ở §14. Chưa có phần nào được implement.*
