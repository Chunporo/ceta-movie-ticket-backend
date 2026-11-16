# CETA — Roadmap product (5 tuần, ops trước, feature sau)

> ⚠️ **BASELINE v1.0 — 16/11/2026.** **§2 của file này (mô hình "bỏ hold") đã bị thay thế.** Hệ thống **có giữ ghế 10 phút** (`HELD`), 1 đơn PENDING/người/suất.
> Đọc thay thế tại `CETA_00_BASELINE_v1.md` §4–§6. Các dòng đã patch bên dưới là đúng; các bước pseudo-code còn lại trong §2 chỉ dùng để hiểu ý, **không dùng để code**.

> Đây là **product thật**, không phải đồ án. Không có hội đồng, không có deadline học kỳ.
> Có khách trả tiền, có hoàn tiền, có người đứng ở rạp lúc 19h thứ 7 không vào được.
> **Bạn là dev + on-call + kế toán + support.** Roadmap này được viết theo đúng thực tế đó.
>
> **Cập nhật 16/11/2026 (14:35) — CHỐT:** **có giữ ghế 10 phút** (`HELD`) ngay khi tạo đơn, mỗi người 1 đơn/suất. Quyết định cuối: `CETA_00_BASELINE_v1.md`.

---

## 0. Ba nguyên tắc chi phối mọi quyết định

| # | Nguyên tắc | Nghĩa là |
|---|---|---|
| P-1 | **Không mất tiền, không mất dữ liệu** — trước mọi thứ khác | Bán trùng ghế, thu tiền 2 lần, vé "ma" là lỗi hạng nhất, không phải bug nhỏ |
| P-2 | **Ops trước feature** | Alert, backup, rollback, đối soát phải xong trước khi mở thêm tính năng |
| P-3 | **Không deploy khi đang bán vé** | Cao điểm 18h–21h t6/t7. Không deploy sau 17h thứ 6 |

---

## 1. Bốn file, và chỉ bốn

| File | Vai trò | Khi nào mở |
|---|---|---|
| `CETA_BAT_DAU_TU_DAY.md` (file này) | **Roadmap + checklist hằng ngày** | Mỗi sáng |
| `CETA_MovieTicket_Spec_v2.md` | Nghiệp vụ + backlog + business rules | Khi cần biết "làm gì, đúng hay sai" |
| `CETA_Backend_Architecture.md` | DDL, luồng, concurrency, deploy, ADR | Khi code — **trừ các mục ghi ⚠️ ở §2.0** |
| `CETA_BA_Review.md` | Bỏ gì / thêm gì / câu hỏi mở | Khi chốt nghiệp vụ với chủ rạp |

**Lưu trữ, không đọc lại:** `movie_ticket_backend.docx`, `movie_ticket_update.docx` · `MOVIE_TICKET.docx` (rỗng — xoá).

---

## 2. Quyết định 14/11: ghế chỉ bị chiếm khi thanh toán thành công

### 2.0 Đổi thành gì

| Trước | Nay |
|---|---|
| Tạo booking là giữ ghế 15' (`RESERVED`) | **Giữ ghế 10' ở `HELD`** ngay khi tạo đơn — nhưng trên bảng DB (`held_until`), nhả được, và mỗi người chỉ 1 đơn/suất |
| Ghế có 4 trạng thái: AVAILABLE / RESERVED / SOLD / BLOCKED | **3 trạng thái: AVAILABLE / SOLD / BLOCKED** |
| Hết 15' → trả ghế | Booking PENDING hết hạn → **sweeper nhả `HELD` về `AVAILABLE`** + booking → EXPIRED, cùng transaction |
| Sweeper phải trả ghế + broadcast | Sweeper chỉ dọn booking rác |
| Xung đột xảy ra **trước** khi trả tiền | Xung đột xảy ra **sau** khi trả tiền |

### 2.1 Luồng mới

```
1. User chọn ghế                    → chỉ ở client
2. POST /bookings
   - đọc showtime_seat, nếu có ghế đã SOLD → 409 ngay (chặn sớm, chỉ là tiện ích)
   - INSERT booking(PENDING, expires_at=now+10') + claim ghế → HELD
   - INSERT payment_transaction + build URL VNPay
   - KHÔNG chạm vào showtime_seat
3. Khách thanh toán tại VNPay         (vài giây → vài phút; ghế vẫn của ai bấm trước)
4. IPN về:
   a. verify chữ ký + amount + tmn + idempotency
   b. **CHIẾM GHẾ — đây là lần duy nhất inventory bị ghi:**
      UPDATE showtime_seat SET status='SOLD', booking_id=:bid, version=version+1
       WHERE showtime_id=:sid AND seat_id IN (:seats) AND status='AVAILABLE'
   c. affected == số ghế  → booking PAID + sinh Ticket + email
   d. affected <  số ghế  → **REFUND_REQUIRED**: không sinh vé, booking giữ PENDING,
      cộng tiền đã thu vào hàng đợi hoàn, gửi email xin lỗi NGAY, báo admin
5. Sweeper 20s: booking PENDING quá hạn → nhả HELD → AVAILABLE + booking EXPIRED
```

Câu `UPDATE` ở 4b vẫn là thứ duy nhất chống bán trùng — nhưng nó chạy **sau khi tiền đã ra khỏi tài khoản khách**, không phải trước.

### 2.2 Cái gì được lợi

| Lợi | Chi tiết |
|---|---|
| Bớt hẳn 1 trạng thái | Không còn `RESERVED`, không còn khái niệm "ghế đang giữ" |
| Sweeper đơn giản | Không trả ghế, không broadcast, chỉ đổi status booking |
| Không có màn hình đếm ngược | Android không cần xử lý hết hạn giữ ghế |
| Seat map nhẹ | Đọc 3 trạng thái, cache dễ, không cần đồng bộ TTL |
| Code ít hơn ~2–3 ngày | Bỏ toàn bộ phần giữ/trả ghế |

### 2.3 Cái gì phải trả giá — đọc kỹ mục này

**Rủi ro mới, nghiêm trọng hơn cái đã bỏ: khách đã trả tiền nhưng không có ghế.**

Hai người có thể cùng trả tiền cho cùng một ghế. Người đến sau mất tiền mà không có vé. Đây là loại sự cố tệ nhất trong bán vé — tệ hơn "không đặt được vé", vì nó đụng đến tiền của khách.

Bắt buộc phải làm, không được bỏ qua:

| Việc | Chi tiết |
|---|---|
| Bật **IPN** (không chỉ return URL) | Cửa sổ xung đột giảm từ 1–5 phút (chờ khách quay về app) xuống 1–3 giây (server-to-server). Đây là biện pháp giảm rủi ro lớn nhất |
| Trạng thái `REFUND_REQUIRED` + hàng đợi admin | Không được để ca này chìm. Phải nhìn thấy mỗi sáng |
| Email xin lỗi **tự động, ngay lập tức** | Trước khi khách kịp gọi. Có mã ca + cam kết thời hạn hoàn |
| Hoàn **toàn bộ** booking, không hoàn một phần | Ghế lẻ làm khách phải ngồi xa nhau — tệ hơn là huỷ cả đơn |
| Cảnh báo trên UI trước khi thanh toán | Một dòng: "Ghế được xác nhận sau khi thanh toán thành công." Không giấu |
| Đo `paid_without_seat` | Ngưỡng: **0**. Có 1 ca là phải xem lại ngay |

Ngưỡng cảnh báo theo baseline: **ghế `HELD` hết hạn mà không thành PAID > 35%** (đo bỏ hoang) hoặc **đơn PENDING đồng thời / tổng ghế > 60%** trong 10 phút liên tục (đo bóp nghẹt thật). Vượt ngưỡng → hạ TTL xuống 5 phút trước khi nghĩ tới tắt hold.

### 2.4 Những mục trong tài liệu kỹ thuật đã lỗi thời

Đọc theo baseline: `Architecture` §5.1 (**có** `HELD`, `held_until`) · §6.2 (claim tại F2) · §6.3 (`HELD → SOLD`) · §6.5 (sweeper nhả ghế). Các mục §4.2 · §7.1 · §8.5 · §14 dòng 2,3 vẫn mô tả mô hình cũ — bỏ qua.
Luồng đúng để code là §2.1 ở trên.

## 3. Thứ tự 5 tuần

Nguyên tắc xếp lịch: **tuần 1–2 bảo vệ tiền → tuần 3 bảo vệ giấc ngủ → tuần 4 bảo vệ sổ sách → tuần 5 mới bán thật.**

### TUẦN 1 — Nền & gate pháp lý

| Ngày | Việc | Xong khi |
|---|---|---|
| 1 | **Gửi hồ sơ merchant VNPay** (gate cứng — §5) · chốt 3 câu nghiệp vụ: bán tại quầy? ghế đôi theo cặp? bắt buộc verify email? | Có câu trả lời bằng văn bản; hồ sơ merchant đã nộp |
| 2 | `application.properties` + profile `dev/prod` + `.env.example`; `h2` → `test` scope; fail-fast khi thiếu secret | App boot từ env, không cần sửa code |
| 3 | `docker-compose.yml` (mysql + api); Flyway `V1__baseline` | `docker compose up` từ repo trắng, health xanh |
| 4 | Bảng `showtime_seat` + entity + repository + sinh dòng khi tạo suất chiếu | 1 suất chiếu = đúng `số ghế của phòng` dòng |
| 5 | Migration `V2` backfill (idempotent) + đối chiếu dữ liệu vé cũ | Chạy lại migration không nhân đôi dòng |

**DoD tuần 1:** clone repo trắng → `docker compose up` → seat map đọc từ inventory đúng.
**Không làm tuần này:** đụng luồng checkout.

### TUẦN 2 — Tiền an toàn (tuần quan trọng nhất)

| Ngày | Việc | Xong khi |
|---|---|---|
| 1 | **IPN + chiếm ghế atomic** trong cùng transaction: `UPDATE ... WHERE status='AVAILABLE'`, kiểm `affected == số ghế` | 20 IPN đồng thời cùng 1 ghế → 1 SOLD, 19 vào nhánh hoàn tiền |
| 2 | **Nhánh thua**: `REFUND_REQUIRED` + email xin lỗi tự động + hàng đợi admin hiển thị được | Ca "trả tiền không có ghế" không bao giờ chìm |
| 3 | `BookingStateMachine` + `booking_status_history` | Không còn `setStatus()` ngoài state machine |
| 4 | Sweeper PENDING quá hạn → nhả ghế + EXPIRED | Đặt rồi bỏ đi → 10' sau ghế về AVAILABLE, booking EXPIRED |
| 5 | Idempotency: `payment_transaction.processed_at` + kiểm amount/tmn-code | IPN 5 lần → 1 xử lý, 1 email, 1 dòng history |

**Quyết định L-5 (A):** return URL gọi **cùng** use case `ApplyPaymentResult` (guard `processed_at`) để dev trên máy local — nơi VNPay không gọi được IPN — vẫn chạy đúng. IPN vẫn là đường chính khi lên production.

**Gate A (cuối tuần 2): chưa được thu tiền thật nếu 4 kịch bản sau chưa xanh**
1. 20 IPN đồng thời cùng 1 ghế → đúng 1 booking PAID, 19 `REFUND_REQUIRED`, **0 vé trùng**
2. Ca thua: email xin lỗi được gửi tự động, có dòng trong hàng đợi hoàn tiền trong < 10 giây
3. IPN lặp 5 lần → không gửi trùng email, không đổi trạng thái 2 lần
4. Thanh toán thất bại → không sinh vé, ghế vẫn AVAILABLE (vì chưa từng bị chiếm)

### TUẦN 3 — Nhìn thấy & ngủ được

| Ngày | Việc | Xong khi |
|---|---|---|
| 1 | Ticket sinh **sau** khi PAID + QR có chữ ký (HMAC, có hạn) | Không tồn tại vé cho booking chưa trả tiền |
| 2 | Check-in API + role **STAFF** + màn hình soát vé | Quét 2 lần → lần 2 báo "vé đã dùng" |
| 3 | **Monitoring**: Actuator health + alert về điện thoại + log có `traceId` | Tắt app → điện thoại nhận cảnh báo trong 5 phút |
| 4 | **Backup tự động + restore thử lần 1** | Restore ra DB mới, dữ liệu khớp |
| 5 | Deploy production + **thử rollback** bằng tag image | Rollback về bản trước trong < 5 phút |

**Gate B (cuối tuần 3):** alert chạy · backup restore được · rollback được. Thiếu 1 trong 3 → chưa go-live.

### TUẦN 4 — Sổ sách & hoàn tiền

| Ngày | Việc | Xong khi |
|---|---|---|
| 1 | Cancel + **hàng đợi hoàn tiền** (màn hình admin: ca nào chưa hoàn, quá hạn bao lâu) | Nhìn thấy "3 ca chưa hoàn" mỗi sáng |
| 2 | **Job đối soát hằng ngày**: tổng VNPay settlement vs tổng booking PAID → email | Lệch 1 đồng là có dòng báo |
| 3 | `email_log` + retry + template chuẩn (xác nhận, vé, huỷ, xin lỗi sự cố) | Không gửi trùng, không mất mail |
| 4 | Rate limit auth + WS auth + secrets ra env; rà log không chứa payload thanh toán | Login sai 6 lần bị chặn |
| 5 | **Runbook** 5 sự cố (§6) + tập xử lý 2 kịch bản giả lập | Có thể xử lý khi không nhớ code |

**Gate C (cuối tuần 4):** đối soát khớp · refund queue hiển thị được · runbook có · pháp lý cơ bản xong (§7).

### TUẦN 5 — Tiền thật, phạm vi nhỏ

| Ngày | Việc | Xong khi |
|---|---|---|
| 1 | 3 test concurrency lên CI + UAT nội bộ theo checklist | `mvn verify` xanh, test chạy trên CI |
| 2 | **Chạy thử bằng tiền thật 1.000đ** — 2 người, 2 thiết bị, 1 suất chiếu | Có giao dịch thật, có vé, có soát vé, có đối soát |
| 3 | Sửa lỗi phát hiện + nạp dữ liệu thật (phim, phòng, giá, lịch chiếu 1 tuần) | Dữ liệu thật, không phải seed demo |
| 4 | **Go-live soft**: 1 phòng, 1 suất chiếu, giới hạn 20 vé, có người trực tại rạp | 20 vé bán được, không sự cố tiền |
| 5 | Đo số ngày đầu + retro: cái gì vỡ, cái gì chậm | Có baseline trước khi mở rộng |

**Sau tuần 5 mới bắt đầu:** promotion, loyalty, review, báo cáo doanh thu, FCM. Trước đó không.

---

## 4. Định nghĩa "go-live"

Chỉ go-live khi **tất cả** đúng:

- [ ] Merchant account VNPay đã active, đã thử 1 giao dịch thật
- [ ] Gate A, B, C đều xanh
- [ ] Alert bắn về điện thoại bạn (đã thử bằng cách tắt app)
- [ ] Backup restore đã thử ít nhất 1 lần
- [ ] Rollback đã thử ít nhất 1 lần
- [ ] Đối soát chạy ra file khớp với VNPay
- [ ] Refund queue có màn hình + có người chịu trách nhiệm
- [ ] Chính sách huỷ–hoàn hiển thị **trước** nút thanh toán
- [ ] Đã thử 1 ca "trả tiền nhưng mất ghế": email xin lỗi tự động + có trong hàng đợi hoàn (§2.3)
- [ ] Có kênh support công bố cho khách (email/Zalo/số điện thoại)
- [ ] Có câu thông báo mẫu khi sự cố
- [ ] Biết ai trực suất chiếu đầu tiên (chính bạn)

---

## 5. Gate cứng: merchant account

| Việc | Vì sao chặn |
|---|---|
| Nộp hồ sơ merchant VNPay **ngay ngày 1** | Merchant onboarding thường yêu cầu pháp nhân/ĐKKD và mất vài tuần. Sandbox không thu được tiền thật |
| Xác nhận luồng hoàn tiền với VNPay | Nếu không biết hoàn thế nào trước khi bán, ca hoàn đầu tiên sẽ làm bạn mất 2 ngày |
| Xác nhận phí + thời gian settlement | Ảnh hưởng trực tiếp đến job đối soát tuần 4 |

Nếu hồ sơ chưa xong trong tuần 1 → vẫn code tiếp, nhưng **không hứa ngày go-live** với chủ rạp.

---

## 6. Runbook tối thiểu (viết ở tuần 4, dùng cả đời)

| Sự cố | Dấu hiệu | Làm gì (3 bước) |
|---|---|---|
| App chết | alert 5xx / health fail | check log → restart container → nếu lặp lại, rollback tag trước |
| MySQL chết | mọi request 503 | khởi động lại service → nếu hỏng dữ liệu, restore backup gần nhất → báo khách đang bị ảnh hưởng |
| Ghế kẹt ở `HELD` | khách báo "ghế không chọn được" | kiểm tra `booking_id`/`held_until`: PENDING quá hạn thì để sweeper dọn (20s) hoặc chạy tay; **không** sửa trực tiếp DB khi đang bán |
| Thanh toán lệch | job đối soát báo lệch | đối chiếu `txn_ref` → nếu VNPay có mà DB không: xử lý tay trong 24h |
| Khách không vào được rạp | support gọi | tra booking theo email/số vé → soát tay bằng màn hình STAFF → ghi lại ca |

---

## 7. Trước khi thu tiền thật — checklist pháp lý & vận hành

- [ ] Điều khoản sử dụng + chính sách huỷ–hoàn hiển thị trước thanh toán, lưu `policy_version` vào booking
- [ ] Chính sách dữ liệu cá nhân: lưu gì, giữ bao lâu, khách xoá tài khoản thế nào *(xác nhận phạm vi tuân thủ với người có chuyên môn — đừng tự suy đoán)*
- [ ] Xác nhận với kế toán: vé xem phim có phát hành hoá đơn không, nếu có cần trường dữ liệu gì
- [ ] Số điện thoại/Zalo support công bố trên app và trên trang thanh toán
- [ ] Câu thông báo mẫu khi sự cố (ngắn, không đổ lỗi, có cách liên hệ)
- [ ] Biết giờ bạn không trực (ngủ) → phương án: bán tay tại quầy bằng role STAFF

---

## 8. Sáu con số phải nhìn mỗi sáng

| Metric | Ngưỡng | Nếu vượt |
|---|---|---|
| Tỷ lệ đặt vé thành công | > 95% | kiểm tra lỗi thanh toán + conflict ghế |
| Tỷ lệ thanh toán thất bại | < 5% | kiểm tra VNPay, không phải code |
| **Số ca trả tiền không có ghế** | **0** | giờ là ca hiếm (late-IPN). > 2 ca/tuần → kiểm tra TTL có quá ngắn so với thời gian nhập thẻ VNPay, không phải bỏ hold |
| Ca hoàn tiền tồn | 0 trong 48h | xử lý ngay trong ngày |
| p95 seat map | < 300 ms | kiểm tra index |
| Tỷ lệ 5xx | < 0,5% | đọc log theo `traceId` |

Lấy bằng một query chạy tay mỗi sáng. Đừng xây dashboard trước khi có dữ liệu thật.

---

## 9. Không làm trong 5 tuần này

**Hold ghế trước khi thanh toán / Redis lock / TTL** (đã bỏ — §2) · WebSocket realtime (dời sang sau go-live, chỉ cần khi có nhu cầu thật) · promotion/coupon · loyalty/điểm · review phim · recommendation · FCM · multi-cinema · microservices · Kafka · Redis cluster · CI/CD cho Android · dashboard doanh thu nâng cao · AWS full stack.

Mỗi mục trên đều **không** làm giảm nguy cơ mất tiền hoặc mất dữ liệu trong 5 tuần tới. Chúng là tăng trưởng — trên nền chưa chắc đúng thì là tăng trưởng âm.

---

## 10. Luật chống mông lung

1. **Việc hôm nay phải nằm trong bảng của tuần hiện tại.** Không nằm trong đó → ghi ra giấy, để sau.
2. **Không mở lại 2 file .docx cũ.** Mọi thứ cần biết đã ở Spec v2.
3. **Không refactor ngoài roadmap.** Code đẹp không cứu được một ca bán trùng ghế.
4. **Mỗi tối commit + ghi 2 dòng: hôm nay xong gì, mai làm gì.** Repo hiện chỉ có 1 commit cho 110 file.
5. **Khi bí, hỏi 1 câu:** "Việc này có làm giảm nguy cơ mất tiền/mất dữ liệu, hoặc tăng doanh thu trong 7 ngày tới không?" Không → để sau.
6. **Không deploy sau 17h thứ 6.** Không có ngoại lệ.

---

## 11. Tra cứu ở đâu

| Bạn cần | Mở |
|---|---|
| Luồng mới (ghế chiếm tại IPN) | **File này §2.1** |
| DDL, migration, index | `Architecture` §5 (bỏ cột `held_by`, `hold_expires_at`) |
| Vì sao chống bán trùng ghế vẫn đúng dù có giữ ghế | `Architecture` §7.2–7.3 (dòng khoá + ranh giới transaction) + `BASELINE_v1` §3 (invariants) |
| API + mã lỗi cho Android | `Architecture` §8 (`SEAT_CONFLICT` trả ở checkout khi đọc seat map; ca thua ở IPN dùng `REFUND_REQUIRED`) |
| Deploy, backup, sự cố | `Architecture` §12 |
| Nghiệp vụ, backlog, con số (15', 2h…) | `Spec_v2` §4, §5 |
| Nghiệp vụ nào bỏ/thêm, câu hỏi cho chủ rạp | `BA_Review` §3, §4, §11 |
| Trả lời "vì sao chọn thiết kế này" | `Architecture` Phụ lục C (ADR) — ADR-002 vẫn đúng, ADR-003 cần sửa |

---

*Một câu: bạn không cần thêm tính năng nào tuần này. Bạn cần một hệ thống không bán trùng ghế và một cái điện thoại biết kêu khi hệ thống chết.*
