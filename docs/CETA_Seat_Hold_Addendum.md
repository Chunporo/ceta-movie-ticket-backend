# CETA — Addendum: Giữ ghế (Seat Hold) có nên thêm lại?

> **Trả lời ngắn: Nên thêm — nhưng là "giữ ghế = cửa sổ thanh toán", 10 phút, mỗi người chỉ 1 đơn mỗi suất.**
> Không thêm một cơ chế mới. Chỉ thêm **một trạng thái** vào bảng đã có.
>
> Tài liệu này supersede (thay thế) quyết định "không giữ ghế" trong `CETA_Backend_Architecture.md` §5.1/§6.2/§6.3 và L-6(B) trong `CETA_BAT_DAU_TU_DAY.md`.

| | |
|---|---|
| Phiên bản | 1.0 — 2026-11-16 |
| Quyết định | **L-8**: bật hold, TTL 10 phút, 1 PENDING/user/suất, có kill switch |
| Thay đổi | L-6(B) bị đảo: nhiều PENDING → **1 PENDING** |
| Giữ nguyên | L-5(A) return URL claim, L-7 (giá chốt ở F2), refund_queue |

---

## 1. Bằng chứng thị trường — ai giữ ghế, ai không

| Chuỗi / app | Có giữ ghế? | Thời gian | Ghi chú |
|---|---|---|---|
| **CGV Việt Nam** | **Không** (theo điều khoản) | — | ToS nêu rõ CGV *không cam kết giữ chỗ ngồi* cho tới khi thanh toán xong ([cgv.vn/terms-use](https://www.cgv.vn/default/terms-use/)) |
| **BookMyShow (Ấn Độ)** | **Có** | **7–8 phút** | Trang hỗ trợ chính thức: ghế bị block khi bạn sang bước tiếp; đơn bỏ dở/hủy/thất bại → nhả sau 7–8 phút ([support.bookmyshow.com](https://support.bookmyshow.com/support/solutions/articles/4000159867-transaction-didn-t-go-thru-and-seats-appeared-to-be-blocked)) |
| Ticketmaster / AMC / Fandango | **Có** | 8–10 phút | Timer hiển thị ở bước checkout; hết giờ phải chọn lại |
| Rạp Hàn (CGV/Megabox/Lotte) | **Có** | ~5–10 phút | Chọn ghế realtime, ghế chuyển "đang chọn" ngay khi bấm |

**Đọc bảng này cho đúng:** CGV VN **không** giữ ghế, nhưng CGV Hàn **có**. BookMyShow — nền tảng có mật độ tranh chấp cao nhất thế giới — **có**, và chỉ 7–8 phút. Tức là: giữ ghế không phải "cao cấp hơn", nó là **lựa chọn giữa hai loại rủi ro**:

| | Không giữ ghế (hiện tại) | Có giữ ghế |
|---|---|---|
| Rủi ro chính | Khách **bị trừ tiền rồi mới biết mất ghế** → hoàn tiền, gọi CSKH, review 1 sao | Ghế **bị giữ mà không ai mua** → người khác không chọn được |
| Ai chịu thiệt | Khách (nặng hơn: mất tiền tạm thời) | Khách khác (nhẹ hơn: phải chọn ghế khác) |
| Chi phí vận hành | refund_queue + đối soát VNPay | gần như 0 |
| Có cần người xử lý? | **Có** — hoàn tiền tay từng ca | Không |

Đây là lý do tôi đổi ý: **rủi ro thứ hai nhẹ hơn nhiều và có thể chặn bằng thiết kế.** Rủi ro thứ nhất thì không chặn được — nó là bản chất của thanh toán trước.

---

## 2. Bài toán số học — "nhiều khách không chọn được ghế" thực chất là gì

Đây là phần quan trọng nhất. Đừng quyết theo cảm giác, hãy tính.

**Giả định cụm rạp của bạn:** 5 phòng × ~120 ghế. Trung bình 2,4 vé/đơn (cặp đôi, nhóm 3).

```
Số đơn có thể cùng lúc trong trạng thái giữ ghế
  = số ghế phòng / số ghế mỗi đơn
  = 120 / 2,4
  ≈ 50 đơn
```

**Kết luận 1 — cái chặn là SỐ GHẾ, không phải THỜI GIAN.**
Dù TTL là 3 phút hay 30 phút, tối đa vẫn chỉ ~50 người giữ được ghế cùng lúc trong một phòng 120 ghế. TTL **không** làm tăng số người giữ được; nó chỉ quyết định **bao lâu một ghế chết còn nằm trong kho**. Đây là chỗ nhiều người thiết kế sai: tưởng giảm TTL sẽ "cho nhiều khách hơn", thực ra giảm TTL chỉ **tái chế ghế nhanh hơn**.

**Kết luận 2 — 50 người thanh toán đồng thời cho MỘT suất là kịch bản cực hiếm.**
Để phòng 120 ghế bị giữ sạch, bạn cần 50 khách *cùng lúc* đang ở bước trả tiền cho đúng một suất chiếu. Với một cụm rạp đơn lẻ, kể cả suất mở màn bom tấn, con số thực tế thường 5–15. **Nghĩa là: giữ ghế an toàn ở quy mô này.** Vấn đề "không chọn được ghế" mà khách than phiền trên các app lớn đến từ **ghế bị bỏ hoang** (TTL dài + không nhả khi bỏ đi), không đến từ việc có cơ chế giữ.

**Kết luận 3 — thứ thực sự gây bóp nghẹt là 2 lỗi vận hành, không phải cơ chế:**
1. Người dùng **giữ nhiều bộ ghế một lúc** (mở 3 tab, 3 lần chọn) → 1 người chiếm 9–12 ghế.
2. **Không nhả khi khách bỏ đi** (tắt app, bấm back) → ghế chết tới hết TTL.

Cả hai đều chặn được bằng thiết kế (§4). → **Thêm hold là đúng, với điều kiện làm đúng 2 điểm này.**

**Kết luận 4 — ngưỡng cần theo dõi, không phải ngưỡng cần sợ.**
Đặt alert khi: `tỷ lệ ghế HELD bị hết hạn mà không thành PAID > 35%` (đo mức bỏ hoang) hoặc `số đơn PENDING đồng thời / tổng ghế > 60%` trong 10 phút liên tục (đo bóp nghẹt thật). Nếu vượt, hạ TTL xuống 5 phút trước khi nghĩ tới tắt hold.

---

## 3. Thiết kế — thêm 1 trạng thái, không thêm cơ chế

### 3.1 Nguyên tắc: hold = PENDING, một đồng hồ

Sai lầm phổ biến: 10 phút giữ ghế **+** 15 phút thanh toán = khách có 25 phút, ghế chết 25 phút. Đừng làm vậy.

```
Bấm "Tiếp tục" ở SeatMap  →  tạo booking PENDING, hết hạn now + 10 phút
                          →  claim ghế: AVAILABLE → HELD, held_until = cùng mốc
        ↓ thanh toán xong (IPN)
     HELD (của booking này) → SOLD,  booking → PAID
        ↓ hết 10 phút
     sweeper: HELD → AVAILABLE,  booking → EXPIRED
```

**Một mốc thời gian duy nhất.** Booking hết hạn ⟺ ghế được nhả. Không có chuyện booking còn sống mà ghế đã mất (trừ ca late-IPN, xử lý ở §3.5).

### 3.2 Thay đổi DDL (so với §5.1 hiện tại)

```sql
-- showtime_seat: thêm 1 trạng thái + 2 cột
ALTER TABLE showtime_seat
  ADD COLUMN held_until  DATETIME NULL,
  ADD COLUMN booking_id  BIGINT   NULL,
  ADD KEY idx_hold (status, held_until),
  ADD KEY idx_hold_booking (booking_id);

-- status: AVAILABLE | HELD | SOLD | BLOCKED   (thêm HELD)
-- Ràng buộc: status='HELD'  ⇔ held_until IS NOT NULL AND booking_id IS NOT NULL
--            status IN ('AVAILABLE','SOLD','BLOCKED') ⇒ cả hai cột NULL
-- Kiểm bằng CHECK constraint + trigger/ service-level assert
```

Không đổi gì ở `booking` (vẫn `PENDING/PAID/CANCELLED/EXPIRED/REFUNDED`). Không thêm bảng `seat_hold` riêng — bảng đó chính là thứ đã gây lỗi kiến trúc cũ (`SeatHold` entity tồn tại mà không ai ghi).

### 3.3 Claim lúc tạo đơn (mới — thay §6.2)

```sql
BEGIN;
-- 1. Khoá dòng suất chiếu để tuần tự hoá việc claim (chống oversell toàn phòng)
SELECT id FROM showtime WHERE id = :showtimeId FOR UPDATE;

-- 2. Đếm ghế còn dùng được — phải khớp đúng số ghế yêu cầu
SELECT COUNT(*) FROM showtime_seat
 WHERE showtime_id = :showtimeId AND seat_id IN (:seatIds)
   AND status = 'AVAILABLE';
-- nếu != n → ROLLBACK, trả 409 kèm danh sách ghế vừa mất

-- 3. Claim điều kiện (không có chuyện claim được 2/3 ghế)
UPDATE showtime_seat
   SET status='HELD', held_until = :expiresAt, booking_id = :bookingId
 WHERE showtime_id = :showtimeId AND seat_id IN (:seatIds)
   AND status = 'AVAILABLE';
-- affected_rows phải = n, nếu không → ROLLBACK

INSERT INTO booking (...) VALUES (..., 'PENDING', :totalAmount);
INSERT INTO booking_seat ... ;   -- snapshot giá từng ghế (L-7)
COMMIT;
```

**Điểm mấu chốt:** `SELECT ... FOR UPDATE` trên dòng `showtime` biến toàn bộ việc claim của một suất thành tuần tự. 5 phòng × 120 ghế, cao điểm vài chục request/phút — MySQL khoá dòng này trong **vài ms**, không phải nút thắt. Không cần Redis cho tính đúng đắn. (Redis chỉ dùng để cache sơ đồ ghế đọc, TTL 3–5 giây, mất cache không ảnh hưởng gì.)

### 3.4 IPN / return (sửa §6.3)

Không còn "claim từ AVAILABLE" ở đường chính:

```sql
BEGIN;
-- 1. Idempotency: chỉ xử lý 1 lần
UPDATE payment_transaction SET processed_at = NOW()
 WHERE provider='VNPAY' AND txn_ref = :txnRef AND processed_at IS NULL;
-- affected = 0 → đã xử lý, trả 200 luôn

-- 2. Kiểm amount + mã ngân hàng + chữ ký (giữ nguyên như cũ)

-- 3. Ghế phải vẫn đang HELD bởi ĐÚNG booking này
UPDATE showtime_seat SET status='SOLD', held_until=NULL
 WHERE booking_id = :bookingId AND status='HELD';
-- affected != n → nhánh refund (§3.5)

UPDATE booking SET status='PAID' WHERE id=:bookingId AND status='PENDING';
-- affected = 0 → nhánh refund
COMMIT;
```

Vẫn giữ điều kiện `status='HELD'` chứ không chỉ `booking_id` — vì sweeper có thể đã nhả ghế giữa lúc VNPay gọi về.

### 3.5 Nhánh hoàn tiền — vẫn phải có

Còn đúng 3 đường dẫn tới `refund_queue`:
| Ca | Nguyên nhân |
|---|---|
| `LATE_PAYMENT` | Khách trả sau 10 phút → ghế đã nhả, người khác mua |
| `DOUBLE_PAY` | VNPay gọi IPN + return cùng lúc (chặn bằng `processed_at`, đây là lưới an toàn) |
| `HOLD_EXPIRED_DURING_PAY` | Mạng chậm, IPN về muộn |

Vì nhánh này **buộc phải tồn tại** dù có hold hay không, chi phí để có kill switch (§6) gần như bằng 0.

### 3.6 API thay đổi

| Endpoint | Thay đổi |
|---|---|
| `POST /api/v1/bookings` | Trả thêm `holdExpiresAt` + `seats[]`; **409 kèm `conflicts[]`** nếu claim thất bại |
| `GET /api/v1/bookings/{id}` | Trả `holdExpiresAt`, `secondsRemaining` |
| `DELETE /api/v1/bookings/{id}` | **Mới**: nhả ghế ngay (owner + PENDING). Body `{reason: USER_CANCELLED / ABANDONED}` |
| `GET /api/v1/showtimes/{id}/seats` | Trả `status` gồm `HELD`; **không** trả `held_until` chi tiết, chỉ `held: true` |

`DELETE` là endpoint quan trọng nhất trong addendum này — nó là thứ biến TTL 10 phút thành TTL 15 giây trong thực tế.

---

## 4. Chống bóp nghẹt — 8 biện pháp, xếp theo hiệu quả/công sức

| # | Biện pháp | Chặn được gì | Công sức |
|---|---|---|---|
| **1** | **1 đơn PENDING / user / suất chiếu** (đảo L-6B) | 1 người giữ 3 bộ ghế. Đây là nguyên nhân số 1 | Rất thấp — 1 unique index có điều kiện |
| **2** | **Nhả khi bỏ đi**: `DELETE /bookings/{id}` khi bấm back; app gọi khi vào background > 90 giây trong lúc thanh toán | Ghế chết 10 phút → chết 15 giây | Thấp |
| **3** | **TTL 10 phút**, không gia hạn | Ghế bị treo lâu | Đã có |
| **4** | **Sweeper 20 giây** (thay vì 60) thay vì chờ job lớn | Trễ nhả ghế | Rất thấp |
| **5** | **Rate limit**: tối đa 3 lần tạo đơn / user / suất / giờ | Quay vòng giữ-rồi-nhả để phá người khác | Thấp |
| **6** | **Hiển thị HELD khác SOLD** trên SeatMap (xám nhạt có gạch chéo vs xám đặc) | Khách hiểu "còn có thể quay lại", không nghĩ app lỗi | Thấp — chỉ UI |
| **7** | **Banner khi suất > 70% bị giữ**: "Suất này đang có nhiều người chọn, ghế có thể thay đổi. Suất gần nhất: 20:45" | Kỳ vọng sai của khách | Thấp |
| **8** | **Hàng chờ (waitlist) cho suất đã hết ghế**: "Báo tôi khi có ghế trống" → push khi có ghế nhả | Khách không chọn được ghế — biến dead-end thành kênh quay lại | Trung bình (1 bảng + 1 job + 1 push) |

**Không cần làm:** Redis distributed lock, virtual waiting room (Queue-it), oversell. Ở quy mô 5 phòng, cả ba đều là giải pháp cho vấn đề bạn không có.

### 4.1 Ràng buộc "1 PENDING/user/suất" bằng SQL

```sql
-- MySQL: unique index có điều kiện không hỗ trợ trực tiếp,
-- nên dùng cột sinh (generated column)
ALTER TABLE booking
  ADD COLUMN active_key VARCHAR(80)
  GENERATED ALWAYS AS (
    CASE WHEN status = 'PENDING'
         THEN CONCAT(user_id, ':', showtime_id) ELSE NULL END
  ) STORED,
  ADD UNIQUE KEY uk_one_pending (active_key);
```

Cột sinh `NULL` khi không PENDING → MySQL cho phép nhiều `NULL` → mỗi user chỉ có tối đa 1 PENDING mỗi suất, còn lịch sử thì vô hạn. Gọn hơn kiểm tra ở tầng service (vẫn nên kiểm để trả lỗi thân thiện, nhưng index là hàng rào thật).

---

## 5. UX khi khách không chọn được ghế — 4 trạng thái phải thiết kế

Đây chính là phần bạn lo. Đừng để khách rơi vào màn hình xám không lối thoát.

| Màn | Kích hoạt | Nội dung | Hành động |
|---|---|---|---|
| **S11 HoldConflict** | `POST /bookings` trả 409 | "Ghế **F7, F8** vừa được người khác chọn. Các ghế bạn chọn khác vẫn còn." — highlight đúng ghế mất, tự refresh sơ đồ | [Chọn lại ghế] · [Xem suất khác] |
| **S12 Contended** | Suất > 70% HELD | Banner trên SeatMap: "Đang có nhiều người chọn suất này — ghế có thể đổi bất cứ lúc nào." | Giữ nguyên, chỉ cảnh báo |
| **S13 SoldOut** | 0 ghế AVAILABLE | "Suất 19:30 đã hết ghế." + 3 suất gần nhất còn ghế | [Báo tôi khi có ghế] · [Suất khác] |
| **S14 HoldExpiring** | Còn ≤ 2 phút ở bước thanh toán | Countdown vàng + "Ghế sẽ được nhả sau 2:00" | [Thanh toán ngay] · [Nhả ghế] |

**Microcopy S11 — chỗ dễ làm hỏng nhất.** Đừng viết "Ghế không còn trống" (khách tưởng app lỗi). Viết đúng sự thật + cho thấy phần còn lại vẫn giữ: *"Ghế F7 vừa có người chọn. Ghế F5 và F6 của bạn vẫn được giữ."* — nhưng **cẩn thận**: nếu claim all-or-nothing (§3.3) thì F5/F6 **chưa** được giữ. Vậy microcopy phải là: *"Ghế F7 vừa được người khác chọn. Bạn chọn lại giúp mình nhé — ghế F5, F6 vẫn còn."* (nói "vẫn còn", không nói "vẫn được giữ"). Đây là loại sai lệch một chữ tạo ra ticket hỗ trợ.

---

## 6. Kill switch — bắt buộc, vì app không rollback được

```properties
app.booking.hold-enabled=true    # tắt = quay về claim-at-IPN
```

**Tắt hold khi:** tỷ lệ `HELD → EXPIRED` > 50% trong 30 phút, **hoặc** số ca `refund_queue` tăng > 5/giờ, **hoặc** suất cao điểm liên tục có > 60% ghế HELD.

Khi tắt: `POST /bookings` **không** claim ghế, chỉ tạo PENDING; IPN claim từ `AVAILABLE` như thiết kế cũ. Vì nhánh refund (§3.5) đã tồn tại, code này **luôn** được test và luôn sẵn sàng — flag chỉ đổi *thời điểm* claim, không đổi logic claim.

Thời gian phản ứng mục tiêu: **< 5 phút** (đổi config + restart hoặc dùng `@RefreshScope`).

---

## 7. Tác động lên tài liệu đã có

| File | Mục cần sửa | Sửa gì |
|---|---|---|
| `CETA_Backend_Architecture.md` | §5.1 | Thêm `HELD` + `held_until` + `booking_id`; bỏ câu "3 trạng thái" |
| | §5.5b `refund_queue` | Giữ nguyên, thêm lý do `HOLD_EXPIRED_DURING_PAY` |
| | §6.2 create-booking | Claim tại F2 (mới), không còn "no claim" |
| | §6.3 callback | Claim = `HELD (owner) → SOLD`, không phải `AVAILABLE → SOLD` |
| | §6.4 return URL | Giữ L-5(A), chỉ đổi câu claim |
| | §10.1 L-7 | Không đổi — giá vẫn chốt ở F2 |
| | ADR-002 | Viết lại: "hold ở tầng DB ledger, không Redis, không bảng riêng" |
| `CETA_BAT_DAU_TU_DAY.md` | L-6(B) | Đảo thành **L-8: 1 PENDING/user/suất**. Ghi rõ vì sao đổi |
| | Roadmap tuần | Thêm 0,5 ngày cho `DELETE /bookings/{id}` + sweeper 20s + index `uk_one_pending` |
| `CETA_Android_Spec.md` | US-08 | Bỏ cảnh báo "ghế chỉ xác nhận sau thanh toán" → thay bằng **countdown 10 phút** |
| | §4 (UI) | Thêm S11–S14; SeatMap phân biệt HELD/SOLD |
| | §7 API | Thêm `DELETE /bookings/{id}`, `holdExpiresAt`, 409 `conflicts[]` |
| | Offline | Ghế **vẫn** không cache (giữ nguyên) |
| `CETA_Android_Test_Release.md` | TC-18, TC-21 | TC-18 → kiểm countdown; TC-21 → 409 conflict là **case chính**, không phải refund |
| | TC mới | Nhả ghế khi back (bấm back → ghế AVAILABLE trong ≤ 20s); background > 90s → nhả; hết 10 phút → S14 hiện đúng |

---

## 8. Cái giá của quyết định này (nói thẳng)

**Được:** khách không bị trừ tiền rồi mới biết mất ghế. Đây là lỗi tệ nhất trong toàn bộ sản phẩm — nó biến một sự cố kỹ thuật thành một sự cố *tài chính* với khách. Giữ ghế đẩy sự cố về phía "phải chọn lại ghế", chấp nhận được.

**Mất:**
1. **+0,5 ngày** công (1 cột sinh, 2 cột, 1 endpoint, 1 nhánh sweeper) — so với 2–3 ngày cho refund_queue + đối soát tay, đây là rẻ hơn.
2. **Thêm 1 chế độ lỗi** phải vận hành: giữ ghế ảo. Chặn bằng §4, nhưng không bao giờ bằng 0.
3. **Test tăng**: phải test 2 chế độ (flag on/off). Nhưng như đã nói, nhánh off **buộc** phải tồn tại cho late-IPN, nên chi phí thật thấp.

**Điều tôi KHÔNG khuyên:** dùng Redis distributed lock cho việc claim. Ở quy mô này, `SELECT ... FOR UPDATE` trên 1 dòng `showtime` là đủ, đúng, và dễ debug hơn. Redis chỉ để đọc cache. Code cũ đã sai đúng ở chỗ này — đừng thay bằng một cơ chế phân tán khác cũng phức tạp tương đương.

---

## 9. Quyết định cần bạn chốt

| ID | Câu hỏi | Đề xuất | Ảnh hưởng nếu chọn khác |
|---|---|---|---|
| **L-8** | Bật hold? | **Có**, 10 phút | Tắt = giữ thiết kế cũ, refund_queue là đường chính |
| **L-9** | TTL bao lâu? | **10 phút** (VNPay cần thời gian nhập thẻ; <8 phút dễ sinh late-IPN) | 15 phút = an toàn hơn cho khách, ghế chết lâu hơn |
| **L-10** | Có làm waitlist ngay bản 1? | **Không** — để bản 1.1, nhưng chừa bảng + API | Làm ngay = +1 ngày, giải quyết đúng cơn đau "hết ghế" |
| **L-11** | 1 PENDING/user/suất — có ngoại lệ? | **Không ngoại lệ** | Ngoại lệ = mở lại đúng lỗ hổng bóp nghẹt số 1 |

**Nếu bạn chỉ đọc một câu:** giữ ghế không làm ít khách chọn được ghế hơn — cái làm ít khách chọn được là *ghế bị giữ bởi người đã bỏ đi*. Nên TTL ngắn + nhả khi bỏ đi + 1 đơn/người/suất là 3 thứ bắt buộc đi kèm, không phải tuỳ chọn.
