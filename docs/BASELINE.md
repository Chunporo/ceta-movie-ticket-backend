# CETA — BASELINE v1.0 (tài liệu chốt để bước vào develop)

| | |
|---|---|
| **Phiên bản** | **1.0 — CHỐT 16/11/2026 14:35** |
| **Hiệu lực** | Từ nay đến khi có v1.1. **Không sửa nội dung tài liệu này giữa sprint** — muốn đổi thì tạo v1.1 và ghi lý do |
| **Quy tắc vàng** | **Nơi nào tài liệu khác mâu thuẫn với file này, file này thắng.** Không hỏi lại, không diễn giải |
| **Trạng thái** | Đã chốt. Dev bắt đầu được ngay |

---

## 0. Bản đồ tài liệu — cái nào là hợp đồng, cái nào là lịch sử

| File | Vai trò | Ai đọc | Có được sửa giữa sprint? |
|---|---|---|---|
| **`CETA_00_BASELINE_v1.md`** (file này) | **HỢP ĐỒNG.** Quyết định + DDL + state machine + API + DoD | Cả team, mỗi ngày | **Không** |
| `CETA_Seat_Hold_Addendum.md` | Lý do kỹ thuật của cơ chế giữ ghế + số liệu thị trường | Dev backend | Không |
| `CETA_Android_Spec.md` | Chi tiết UI/UX + PRD app (đã patch theo baseline) | Dev Android | Chỉ microcopy |
| `CETA_Android_Test_Release.md` | Test case + quy trình phát hành | Dev + QA | Có (thêm case) |
| `CETA_Backend_Architecture.md` | Thiết kế L0→L4 chi tiết (đã patch; vài mục bị supersede) | Dev backend khi cần đào sâu | Không sửa, chỉ đọc |
| `CETA_BAT_DAU_TU_DAY.md` | Roadmap 5 tuần, runbook, pháp lý | Bạn (owner) | Có |
| `CETA_MovieTicket_Spec_v2.md`, `CETA_BA_Review.md` | Lịch sử phân tích + backlog gốc | Khi cần tra nguồn gốc quyết định | Không |

---

## 1. Phạm vi bản 1.0 — chốt cứng

**Làm:** 1 cụm rạp · 1 chi nhánh · đặt vé online · VNPay · vé QR · web admin cho nhân viên · Android app cho khách.
**Không làm (bản 1):** iOS · web cho khách · bán tại quầy qua app · combo/bắp nước · tích điểm · voucher · đa ngôn ngữ · microservices · Kafka · Kubernetes.

Kiến trúc chốt: **monolith Spring Boot 3.4 / Java 21 / MySQL 8 / Redis chỉ để cache đọc**. Không Redis lock, không Redis là nguồn sự thật. Không message queue.

---

## 2. Quyết định đã chốt (D-xx) — không bàn lại

### 2.1 Tồn kho ghế

| ID | Quyết định | Trạng thái |
|---|---|---|
| **D-01** | **Có giữ ghế.** Ghế `HELD` ngay khi tạo đơn, TTL **10 phút**, một đồng hồ duy nhất (= cửa sổ thanh toán) | Chốt (thay "không giữ ghế" của 14/11) |
| **D-02** | **1 booking PENDING / user / suất chiếu.** Enforce bằng unique index trên cột sinh — không chỉ bằng code | Chốt (đảo L-6B) |
| **D-03** | Claim ghế = `SELECT ... FOR UPDATE` trên dòng `showtime` + `UPDATE ... WHERE status='AVAILABLE'`, `affected_rows` phải = n. All-or-nothing | Chốt |
| **D-04** | Claim thất bại → **409 + `conflicts[]`**, khách chọn lại **trước khi trả tiền**. Không bao giờ claim 2/3 ghế | Chốt |
| **D-05** | Nhả ghế ngay khi khách bỏ đi: `DELETE /bookings/{id}`; app gọi khi back hoặc background > 90s | Chốt |
| **D-06** | Sweeper **20 giây**: `HELD` quá `held_until` → `AVAILABLE`; `PENDING` → `EXPIRED`, trong cùng transaction | Chốt |
| **D-07** | `refund_queue` **vẫn tồn tại** — chỉ dành cho ca late-IPN / double-IPN. Không phải đường chính | Chốt |
| **D-08** | **Kill switch** `app.booking.hold-enabled`. Tắt → quay về claim-at-IPN trong < 5 phút | Chốt (bắt buộc) |

### 2.2 Thanh toán

| ID | Quyết định |
|---|---|
| **D-09** | `payment_transaction` unique `(provider, txn_ref)`, `processed_at` là cờ idempotency. `txn_ref = bookingId_attemptNo` |
| **D-10** | IPN là đường chính. Return URL gọi **cùng** `ApplyPaymentResultUseCase`, guard bằng `processed_at` (L-5A) |
| **D-11** | Kiểm **đủ 3 thứ** trước khi nhận tiền: chữ ký · `vnp_Amount` khớp `booking.totalAmount` · `vnp_TmnCode` khớp config. Thiếu một cái → từ chối + log SECURITY |
| **D-12** | `booking.totalAmount` chốt tại F2. Vé phân bổ từ đó, **không gọi lại `pricingService.calculate()`** lúc claim (L-7). Assert `Σ ticket.price + comboAmount − discountAmount − pointsAmount == totalAmount` (đổi từ `Σ ticket.price == totalAmount` khi thêm combo bắp nước và voucher; `discount_amount` có từ V12, `points_amount` thêm ở B5, trước đó bằng 0). **Tổng chỉ đổi được khi booking còn `PENDING` và chưa có giao dịch thanh toán nào**: `PUT /bookings/{id}/extras` thay combo và voucher, tính lại từ giá ghế đã chốt ở `booking_seat` (không gọi lại `pricingService`). Từ lần `create-url` đầu tiên tổng là cuối cùng (409 `PAYMENT_ALREADY_STARTED`), nên `vnp_Amount` luôn khớp |
| **D-13** | Hoàn tiền **bán tự động**: admin bấm trên web, đối soát tay với VNPay. Bản 1 không gọi API refund |

### 2.3 Nghiệp vụ

| ID | Quyết định | Ghi chú |
|---|---|---|
| **D-14** | Cửa sổ thanh toán **10 phút** (giữ ghế = cửa sổ này) | L-9 |
| **D-15** | Huỷ vé: **trước giờ chiếu ≥ 60 phút**. Sau đó khoá | Benchmark CGV |
| **D-16** | Tối đa **8 ghế/đơn** | |
| **D-17** | Mở bán **T-14 ngày** | |
| **D-18** | **SWEETBOX bán theo cặp** — ghế đôi là 1 đơn vị bán, không bán lẻ nửa cặp | Q-01 chốt |
| **D-19** | **Không bán tại quầy qua app.** Nhân viên dùng web admin. **Không có role STAFF trong app** | Q-02 chốt |
| **D-20** | **Không bắt buộc verify email** để mua. Chỉ cần khi khách yêu cầu gửi lại vé / hoàn tiền | Q-03 chốt — giảm ma sát |
| **D-21** | **Không xuất hoá đơn VAT** bản 1. ToS ghi rõ vé điện tử không phải hoá đơn | Q-04 chốt |
| **D-22** | Huỷ suất chiếu: toàn bộ vé `PAID` → `REFUNDED`, gửi email + push, admin **bắt buộc** nhập lý do | Q-07 chốt |
| **D-23** | QR **tĩnh theo vé**, soát trên web (nhân viên). App khách **không có camera**, không quét | |
| **D-24** | Giá: `pricing_policy` + chuỗi rule phòng → loại ghế → khung giờ, snapshot vào `booking_seat` | |
| **D-25** | Đa ngôn ngữ: bản 1 **chỉ tiếng Việt**. String nằm trong `strings.xml` để sẵn | |

---

## 3. Bất biến (invariants) — vi phạm là bug P0, không phải "trade-off"

1. Không có hai `ticket` sống cho cùng `(showtime_id, seat_id)`.
2. `showtime_seat.status = 'HELD'` ⟺ `held_until IS NOT NULL AND booking_id IS NOT NULL`.
3. `HELD` luôn thuộc về **đúng một** booking, và booking đó phải `PENDING`.
4. Không booking `PAID` nào thiếu ghế; không booking `PENDING` nào giữ ghế quá `held_until + 60s`.
5. `Σ ticket.price + combo_amount − discount_amount − points_amount == booking.totalAmount` — assert trước khi commit IPN (`points_amount`: B5, trước đó 0).
6. Một lần gọi IPN với cùng `txn_ref` chỉ tạo **một** vé.
7. `showtime_seat` chỉ được UPDATE bởi module `inventory` — enforce bằng ArchUnit.
8. Không logic nào dựa vào `redisTemplate.keys()` / `hasKey()`.

---

## 4. DDL chốt

```sql
-- ============ Tồn kho ghế ============
CREATE TABLE showtime_seat (
  showtime_id BIGINT NOT NULL,
  seat_id     BIGINT NOT NULL,
  status      ENUM('AVAILABLE','HELD','SOLD','BLOCKED') NOT NULL DEFAULT 'AVAILABLE',
  held_until  DATETIME NULL,
  booking_id  BIGINT NULL,
  updated_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (showtime_id, seat_id),
  KEY idx_hold (status, held_until),
  KEY idx_hold_booking (booking_id),
  CONSTRAINT fk_ss_showtime FOREIGN KEY (showtime_id) REFERENCES showtime(id),
  CONSTRAINT fk_ss_seat     FOREIGN KEY (seat_id)     REFERENCES seat(id),
  CONSTRAINT ck_held CHECK (
    (status = 'HELD' AND held_until IS NOT NULL AND booking_id IS NOT NULL)
    OR (status <> 'HELD' AND held_until IS NULL AND booking_id IS NULL)
  )
) ENGINE=InnoDB;

-- ============ Booking ============
CREATE TABLE booking (
  id            BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id       BIGINT NOT NULL,
  showtime_id   BIGINT NOT NULL,
  status        ENUM('PENDING','PAID','CANCELLED','EXPIRED','REFUNDED') NOT NULL,
  total_amount  BIGINT NOT NULL,           -- chốt tại F2 (D-12) = ghế + combo_amount (− giảm giá − điểm, B4/B5)
  combo_amount  BIGINT NOT NULL DEFAULT 0, -- V11: phần bắp nước của total_amount; dòng chi tiết ở booking_combo
  discount_amount BIGINT NOT NULL DEFAULT 0, -- V12: giảm giá voucher (chỉ trên tiền vé); voucher dùng ở booking_promotion
  hold_expires_at DATETIME NOT NULL,       -- = held_until của ghế
  created_at    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  paid_at       DATETIME NULL,
  active_key    VARCHAR(80) GENERATED ALWAYS AS (
                  CASE WHEN status = 'PENDING'
                       THEN CONCAT(user_id, ':', showtime_id) ELSE NULL END
                ) STORED,
  UNIQUE KEY uk_one_pending (active_key),  -- D-02
  KEY idx_expiry (status, hold_expires_at)
) ENGINE=InnoDB;

-- ============ Thanh toán ============
CREATE TABLE payment_transaction (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  booking_id   BIGINT NOT NULL,
  provider     VARCHAR(20) NOT NULL,       -- VNPAY
  txn_ref      VARCHAR(64) NOT NULL,       -- bookingId_attemptNo
  provider_txn VARCHAR(64) NULL,           -- vnp_TransactionNo
  amount       BIGINT NOT NULL,
  status       VARCHAR(20) NOT NULL,
  raw_payload  JSON NULL,
  processed_at DATETIME NULL,              -- cờ idempotency (D-09)
  created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_provider_txn (provider, txn_ref)
) ENGINE=InnoDB;

-- ============ Hoàn tiền (chỉ ca hiếm) ============
CREATE TABLE refund_queue (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  booking_id  BIGINT NOT NULL,
  reason      ENUM('LATE_PAYMENT','DOUBLE_PAY','HOLD_EXPIRED_DURING_PAY') NOT NULL,
  amount      BIGINT NOT NULL,
  status      ENUM('PENDING','DONE','FAILED') NOT NULL DEFAULT 'PENDING',
  note        VARCHAR(500) NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  done_at     DATETIME NULL,
  UNIQUE KEY uk_refund_booking (booking_id)   -- không hoàn 2 lần
) ENGINE=InnoDB;

-- ============ Audit ============
CREATE TABLE booking_status_history (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  booking_id BIGINT NOT NULL,
  from_status VARCHAR(20) NULL,
  to_status   VARCHAR(20) NOT NULL,
  reason      VARCHAR(100) NULL,
  created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_bsh_booking (booking_id)
) ENGINE=InnoDB;
```

**Migration bắt buộc chạy trước khi code:** `V1__baseline.sql` tạo toàn bộ bảng trên + bảng cũ (`movie`, `showtime`, `room`, `seat`, `pricing_policy`). Không dùng `ddl-auto=update` ở bất kỳ môi trường nào (kể cả dev).

---

## 5. State machine chốt

### 5.1 Booking

```
                    ┌──────────────── IPN/return OK ────────────────┐
                    │                                                ▼
[F2] PENDING ─────────────────────────────────────────────────► PAID ──► REFUNDED
   │  │                                                            (huỷ suất / hoàn tay)
   │  │  hết 10' (sweeper)          IPN đến sau khi đã EXPIRED
   │  └──────────────► EXPIRED ────────────► REFUNDED
   │                     ▲                   (LATE_PAYMENT → refund_queue)
   │  khách huỷ (DELETE)
   └────────────────► CANCELLED
```

| Từ | Sang | Ai làm | Điều kiện |
|---|---|---|---|
| PENDING | PAID | IPN/return | `processed_at` NULL, chữ ký + amount + tmn khớp, claim ghế OK |
| PENDING | EXPIRED | sweeper 20s | `hold_expires_at < now()` — `UPDATE ... WHERE status='PENDING'` |
| PENDING | CANCELLED | user | cùng user, còn hạn → nhả ghế ngay |
| EXPIRED | REFUNDED | IPN muộn | claim thất bại → `refund_queue(LATE_PAYMENT)` |
| PAID | REFUNDED | admin | huỷ suất hoặc hoàn tay |
| PAID | CANCELLED | **không được phép** | phải đi qua REFUNDED |

### 5.2 Ghế

```
AVAILABLE ──claim(F2)──► HELD ──IPN──► SOLD
    ▲                      │
    └──────sweeper / DELETE / hết hạn──────┘
BLOCKED: chỉ admin đặt (ghế hỏng, giữ cho suất khác). Không tự chuyển.
```

---

## 6. Luồng chốt

| Bước | Endpoint | Ghi chú |
|---|---|---|
| F1 | `GET /api/v1/showtimes/{id}/seats` | Trả `status` gồm `HELD` (chỉ boolean, không trả giờ), cache 3–5s |
| F2 | `POST /api/v1/bookings` | **Claim ghế** + tạo PENDING. 409 nếu thua |
| F3 | `POST /api/v1/payments/vnpay/ipn` | HELD → SOLD, PENDING → PAID, sinh vé |
| F3' | `GET /api/v1/payments/vnpay/return` | Cùng use case, guard `processed_at` |
| F4 | `GET /api/v1/bookings/{id}` | App poll để biết kết quả thật (**nguồn sự thật duy nhất**) |
| F5 | `DELETE /api/v1/bookings/{id}` | Nhả ghế ngay |
| — | `POST /api/v1/bookings/expire-sweep` (job) | Sweeper 20s |

**Thứ tự bắt buộc trong F2:** lock showtime → đếm AVAILABLE → update → insert booking → insert booking_seat → commit. Sai thứ tự = oversell.

---

## 7. API contract chốt (app dùng)

| Method | Path | Response chính | Mã lỗi |
|---|---|---|---|
| POST | `/auth/login` | access + refresh | 401, 423 (khoá tạm) |
| GET | `/movies?page&size&q` | phân trang | — |
| GET | `/showtimes?movieId&date` | danh sách suất | — |
| GET | `/showtimes/{id}/seats` | `[{seatId,label,type,price,status}]` | 404 |
| POST | `/bookings` `{showtimeId, seatIds[], combos?[{comboId,quantity}], voucherCode?}` | `{bookingId, holdExpiresAt, totalAmount, seats[], comboAmount, combos[], discountAmount, voucherCode}` | **409 + `conflicts[]`**, 429, 400 (`COMBO_UNAVAILABLE`, `MAX_SEATS_EXCEEDED`) |
| PUT | `/bookings/{id}/extras` `{combos[], voucherCode}` | `{totalAmount, comboAmount, discountAmount, voucherCode, combos[]}` — thay toàn bộ combo + voucher khi `PENDING` và chưa thanh toán | 400 `VOUCHER_INVALID`/`COMBO_UNAVAILABLE`, 409 `VOUCHER_NOT_ELIGIBLE`/`PAYMENT_ALREADY_STARTED`/`BOOKING_NOT_EDITABLE`/`HOLD_EXPIRED` |
| GET | `/promotions` | ưu đãi đang chạy (công khai) | — |
| GET/PUT/DELETE | `/vouchers`, `/vouchers/{promotionId}` | ví voucher của khách (lưu / bỏ lưu) | 404 |
| GET | `/combos` | `[{id,code,name,nameEn,description,descriptionEn,imageUrl,price}]` (chỉ combo đang bán) | — |
| DELETE | `/bookings/{id}` | 204 | 403, 409 |
| GET | `/bookings/{id}` | `{status, holdExpiresAt, secondsRemaining, tickets[]}` | 404 |
| POST | `/payments/vnpay/create-url` | `{payUrl, txnRef}` | 409, 502 |
| GET | `/tickets/my?page` | danh sách vé | — |
| GET | `/tickets/{id}` | vé + QR payload | 404 |

**Quy ước lỗi chung:** `{code, message, details[]}`; `code` là hằng chuỗi (`SEAT_CONFLICT`, `HOLD_EXPIRED`, `BOOKING_ALREADY_PENDING`), app map sang tiếng Việt. App **không** parse `message`.

---

## 8. Cấu hình chốt

```properties
app.booking.hold-enabled=true
app.booking.hold-ttl=10m
app.booking.max-seats-per-booking=8
app.booking.max-attempts-per-showtime-per-hour=3
app.booking.sweeper-interval=20s
app.booking.cancel-cutoff-minutes=60
app.payment.return-claim=true
app.payment.sell-window-days=14
app.payment.vnpay.tmn-code=${VNPAY_TMN_CODE}
app.payment.vnpay.hash-secret=${VNPAY_HASH_SECRET}
```

Không có secret nào trong repo. `application.properties` phải tồn tại trong `src/main/resources` (lỗi cũ: chỉ có `application-dev.properties`).

---

## 9. Definition of Done — phase 1 (10 ngày dev)

| Ngày | Việc | Done khi |
|---|---|---|
| **D1** | `V1__baseline.sql` + module `inventory` | Chạy được trên MySQL trống; ArchUnit chặn UPDATE ngoài module |
| **D2** | `InventoryService.claim()/release()/promote()` + unit test | Test 3 case: claim đủ, claim thiếu, release idempotent |
| **D3** | `POST /bookings` + 409 `conflicts[]` | **Test đồng thời 50 request cùng 1 ghế → đúng 1 thành công** |
| **D4** | `DELETE /bookings`, `GET /bookings/{id}` | Nhả ghế < 1s; `secondsRemaining` đúng |
| **D5** | IPN + return + idempotency + kiểm amount/tmn | Gọi IPN 5 lần → 1 vé, 0 lỗi |
| **D6** | `refund_queue` + nhánh late-IPN | Seed: expire rồi IPN → 1 dòng refund, không có vé |
| **D7** | Sweeper + kill switch | Tắt flag → claim chuyển về IPN, test lại D3 xanh |
| **D8** | Auth + `@PreAuthorize` từng endpoint + bỏ fallback `hasRole('ADMIN')` | Bảng quyền đầy đủ, test 403 |
| **D9** | Web admin: refund queue + soát QR + huỷ suất | Nhân viên soát được vé trên điện thoại |
| **D10** | E2E staging + VNPay sandbox + smoke | 1 giao dịch sandbox end-to-end xanh |

**Gate A** (cuối D10): chỉ được sang Android khi D1–D7 xanh **và** test đồng thời D3 pass. Nếu chưa, Android làm UI tĩnh bằng mock, không nối API.

**Gate B** (trước production): 1 giao dịch **tiền thật** số tiền nhỏ, đối soát khớp.

**Gate C** (trước khi mở bán công khai): diễn tập sự cố — tắt mạng giữa IPN, kill app giữa thanh toán, 2 máy tranh 1 ghế, hoàn tiền tay 1 ca.

---

## 10. Đổi gì so với tài liệu 14/11

| Nội dung | 14/11 | **16/11 (chốt)** |
|---|---|---|
| Ghế trước thanh toán | Không giữ | **HELD 10 phút** |
| Trạng thái ghế | 3 | **4 (+HELD)** |
| Claim ở đâu | IPN | **F2 (tạo đơn)**, IPN chỉ promote |
| PENDING/user/suất | Nhiều | **1** (unique index) |
| Sweeper | 60s, không đụng ghế | **20s, nhả ghế** |
| Cửa sổ thanh toán | 15' | **10'** |
| Màn hình app | Không countdown | **Countdown + S11–S14** |
| `refund_queue` | Đường chính | **Chỉ ca hiếm** |
| Vai trò trong app | — | **Chỉ CUSTOMER** (D-19) |

---

## 11. Việc còn treo — **không chặn dev**

| ID | Việc | Chặn gì | Chốt tạm để làm tiếp |
|---|---|---|---|
| **X-1** | **Tài khoản VNPay merchant** (đang ở đâu, sandbox có chưa) | Chặn **Gate B**, không chặn code | Code theo sandbox; nếu chưa có sandbox thì mock theo spec VNPay 2.1.0 |
| X-2 | Logo, màu thương hiệu | Không chặn | Dùng placeholder, đổi sau |
| X-3 | Giá vé thật theo phòng/loại ghế | Không chặn | Seed giá giả, cấu trúc đã chốt |
| X-4 | Có bao nhiêu phòng, sơ đồ ghế thật | Chặn dữ liệu seed | Tự dựng 1 phòng 120 ghế để test |

**Nguyên tắc:** chỉ X-1 là thứ có thể làm dừng dự án sau này (không nhận được tiền). Kiểm tra nó **song song** trong tuần 1, đừng để đến D10 mới hỏi.

---

## 12. Ba câu để tự kiểm tra trước khi code dòng đầu tiên

1. Nếu 2 khách bấm "Thanh toán" cho cùng ghế F7 **cùng một giây**, chuyện gì xảy ra? → Một người nhận 409 ngay, người kia vào VNPay. **Không ai bị trừ tiền.**
2. Nếu VNPay gọi IPN **hai lần**? → `processed_at` chặn lần hai, 1 vé.
3. Nếu khách trả tiền **sau** khi ghế đã bị nhả? → `refund_queue(LATE_PAYMENT)`, không có vé, admin hoàn tay trong 24h.

Trả lời được cả ba mà không cần mở tài liệu → bạn đã nắm baseline.
