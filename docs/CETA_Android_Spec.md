# CETA Android — PRD + UI + Technical Design

> ⚠️ **BASELINE v1.0 — 16/11/2026.** App **có countdown giữ ghế 10 phút**. Nơi nào mâu thuẫn, `CETA_00_BASELINE_v1.md` thắng.
> Đã patch: US-08, US-12, S7, bảng màn hình, §4 luồng thanh toán. API chi tiết: baseline §7.

> Gộp 4 tài liệu: **PRD** · **User stories & acceptance criteria** · **UI/UX flows & states** · **Technical design + API/data**
> Bản backend tương ứng: `CETA_Backend_Architecture.md`. Mọi hành vi app dưới đây phải khớp với backend đó.

| | |
|---|---|
| Phiên bản | 1.0 — 2026-11-14 |
| Nền tảng | Android (Kotlin + Jetpack Compose) |
| Trạng thái | Spec để build — chưa implement |
| Quyết định nền tảng | **Không giữ ghế trước khi thanh toán.** Ghế chỉ thuộc về khách khi backend nhận được kết quả thanh toán thành công |

---

## 1. PRD (1 trang)

### 1.1 Mục đích
Cho khách của một cụm rạp đặt vé và nhận vé điện tử trên điện thoại, không cần ra quầy, không cần gọi điện.

### 1.2 Người dùng
| Nhóm | Nhu cầu chính | Điều họ sợ nhất |
|---|---|---|
| Khách quen (2–4 lần/tháng) | Đặt nhanh, chọn đúng ghế quen | Mất ghế sau khi đã trả tiền |
| Khách đi cặp/nhóm | Chọn nhiều ghế liền nhau | Ghế bị tách |
| Khách lần đầu | Hiểu ngay cách đặt | Không biết vé nằm ở đâu để vào rạp |
| Phụ huynh mua cho con | Mua nhanh, ít bước | Nhập sai thông tin |

### 1.3 Vấn đề cần giải
1. Khách phải ra quầy hoặc gọi điện để giữ ghế → mất thời gian, rạp quá tải giờ cao điểm.
2. Vé giấy dễ mất, khó tra cứu khi cần hủy.
3. Rạp không biết ai sẽ đến → không tối ưu được suất chiếu.

### 1.4 Phạm vi bản 1 (launch)
| Có | Không có (bản sau) |
|---|---|
| Đăng ký / đăng nhập / quên mật khẩu | Đăng nhập Google/Facebook |
| Xem phim, suất chiếu, chọn ghế | Gợi ý phim, đánh giá phim |
| Thanh toán VNPay qua WebView | Ví điện tử khác, trả sau |
| Vé điện tử + QR + soát vé tại cổng | Chuyển vé cho người khác |
| Xem lịch sử, hủy vé theo chính sách | Đổi ghế / đổi suất |
| Thông báo khi đơn hoàn tất / thất bại | Push notification theo khuyến mãi |

### 1.5 Tiêu chí thành công (đo sau 30 ngày)
| Chỉ số | Ngưỡng |
|---|---|
| Crash-free sessions | ≥ 99,5% |
| Tỷ lệ đặt vé thành công (vào checkout → có vé) | ≥ 95% |
| Tỷ lệ bỏ dở ở màn thanh toán | ≤ 20% |
| Thời gian từ mở app → có vé (P50) | ≤ 3 phút |
| Ca "trả tiền nhưng không có ghế" | 0 (đo bằng refund queue) |
| ANR rate | ≤ 0,5% |

### 1.6 Ràng buộc
- App là **client mỏng**: không giữ luật nghiệp vụ. Mọi quyết định giá/ghế/trạng thái do backend.
- Chỉ hoạt động khi có mạng cho luồng mua vé (không hỗ trợ offline booking).
- Ngôn ngữ: tiếng Việt. Đơn vị tiền: VND, không phần lẻ.

---

## 2. User stories & acceptance criteria

Định dạng AC: **Khi** … **thì** … (bắt buộc có cả nhánh lỗi).

| ID | Story | Acceptance criteria |
|---|---|---|
| US-01 | Là khách, tôi đăng nhập để đặt vé | Khi nhập đúng email + mật khẩu, app mở màn hình chính. Khi sai, hiện lỗi dưới ô mật khẩu và cho thử lại; sai 5 lần liên tiếp thì hiện thông báo chờ 1 phút |
| US-02 | Là khách mới, tôi đăng ký tài khoản | Khi email chưa tồn tại và mật khẩu ≥ 8 ký tự, tài khoản được tạo và app tự đăng nhập. Khi email đã tồn tại, hiện "Email này đã được dùng" kèm nút Đăng nhập |
| US-03 | Tôi quên mật khẩu | Khi bấm "Quên mật khẩu" và nhập email, app hiện "Nếu email tồn tại, bạn sẽ nhận được hướng dẫn" — **không** tiết lộ email có tồn tại hay không |
| US-04 | Tôi xem danh sách phim đang chiếu | Khi mở tab Phim, app hiện phim đang chiếu + sắp chiếu, có tìm kiếm theo tên và lọc theo thể loại. Khi mất mạng, hiện dữ liệu cache kèm banner "Đang xem dữ liệu đã lưu" |
| US-05 | Tôi chọn suất chiếu | Khi chọn phim, app hiện các ngày có suất chiếu và giờ chiếu còn bán. Suất đã bắt đầu hoặc đã đóng bán hiển thị mờ, không bấm được |
| US-06 | Tôi chọn ghế | Khi mở sơ đồ ghế, ghế AVAILABLE hiện rõ, ghế SOLD mờ + không bấm được, ghế BLOCKED không hiển thị như chỗ ngồi. Chọn quá 8 ghế thì báo "Tối đa 8 ghế mỗi đơn" |
| US-07 | Tôi biết tổng tiền trước khi trả | Khi chọn ghế, thanh dưới cùng hiện số ghế + tổng tiền cập nhật ngay, không cần chờ server |
| US-08 | Tôi biết mình có bao nhiêu thời gian để trả tiền | Khi bấm "Tiếp tục" ở SeatMap, app tạo đơn và hiện **countdown 10:00** ở S7, đếm ngược theo `holdExpiresAt` từ server (không tự đếm). Còn ≤ 2 phút → đổi màu vàng + hiện S14 |
| US-08b | Tôi hiểu hết giờ thì mất ghế | Microcopy dưới countdown: "Ghế được giữ trong 10 phút. Hết giờ, ghế sẽ được nhả cho người khác." — không dùng từ "có thể", phải dứt khoát |
| US-09 | Tôi thanh toán | Khi bấm xác nhận, app tạo booking trên backend rồi mở VNPay trong WebView. Nếu tạo booking thất bại (ghế vừa bán), quay lại sơ đồ ghế, tô đỏ ghế vừa mất và cho chọn lại |
| US-10 | Tôi trở về từ VNPay | Khi thanh toán xong và app được mở lại, app **không** tự kết luận thành công — gọi `GET /bookings/{id}` và hiển thị theo trạng thái thật. Trong lúc chờ, hiện "Đang xác nhận thanh toán…" và poll 3 lần cách 2 giây |
| US-11 | Tôi nhận được vé | Khi booking ở trạng thái PAID, app hiện vé có QR, tên phim, rạp, phòng, ghế, giờ chiếu, mã vé |
| US-12 | Ghế tôi chọn vừa bị người khác lấy | Khi `POST /bookings` trả **409** kèm `conflicts[]`, app hiện **S11**: "Ghế F7 vừa được người khác chọn. Bạn chọn lại giúp mình nhé." — highlight đúng ghế mất, refresh sơ đồ, **không** sang bước thanh toán. Nói "vẫn còn", **không** nói "vẫn được giữ" |
| US-13 | Đã trả tiền nhưng không còn ghế (ca hiếm) | Khi `GET /bookings/{id}` trả `REFUNDED`, app hiện **S10 RefundNotice**: xin lỗi + mã đơn + "hoàn trong 24 giờ" + nút liên hệ. **Không** hiện vé, không hiện màn thành công |
| US-13 | Tôi xem lịch sử đặt vé | Khi mở tab Vé, app hiện đơn mới nhất trước, phân trang khi cuộn. Đơn PENDING quá 15' hiển thị "Đã hết hạn" |
| US-14 | Tôi hủy vé | Khi bấm Hủy và còn ≥ 60 phút trước giờ chiếu, app hỏi xác nhận rồi hủy. Nếu chưa tới hạn, nút Hủy bị ẩn kèm chú thích "Chỉ hủy được trước 60 phút" |
| US-15 | Tôi vào rạp | Khi mở vé, QR hiển thị đủ lớn và tăng độ sáng màn hình; có nút "Mã vé" dạng chữ để đọc khi QR lỗi |
| US-16 | Tôi đổi mật khẩu | Khi nhập đúng mật khẩu cũ và mật khẩu mới hợp lệ, app đăng xuất khỏi các thiết bị khác và yêu cầu đăng nhập lại |
| US-17 | Tôi xóa tài khoản | Khi xác nhận xóa, app gọi API xóa, xóa token cục bộ và về màn đăng nhập. Nếu còn đơn đang PENDING, app cảnh báo trước |

**Bắt buộc với mọi story:** có trạng thái loading · có trạng thái lỗi · có đường thoát (thử lại hoặc quay về).

---

## 3. UI/UX — luồng, màn hình, trạng thái

### 3.1 Sơ đồ điều hướng

```
Splash
  ├─(có token hợp lệ)→ Home
  └─(không)──────────→ Login ⇄ Register ⇄ ForgotPassword
                              ↓
Home (Tabs: Phim · Vé của tôi · Tài khoản)
  └─ Phim → MovieDetail → ShowtimeList → SeatMap → Checkout
                                                      ↓
                                              PaymentWebView
                                                      ↓
                                          PaymentResult
                                    ├─ SUCCESS → TicketDetail
                                    ├─ FAILED  → SeatMap (chọn lại)
                                    ├─ REFUND_PENDING → RefundNotice
                                    └─ PROCESSING → poll → …
Vé của tôi → TicketList → TicketDetail (QR)
Tài khoản → EditProfile · ChangePassword · Notifications · Support · DeleteAccount
```

### 3.2 Danh sách màn hình

| # | Màn hình | Thành phần chính | Ghi chú |
|---|---|---|---|
| S1 | Splash | logo, kiểm tra token | ≤ 1s, không chặn nếu offline |
| S2 | Login | email, mật khẩu, quên MK, đăng ký | hiện lỗi tại chỗ |
| S3 | Register | email, mật khẩu, nhập lại, điều khoản | checkbox điều khoản bắt buộc |
| S4 | Home – Phim | search, chip thể loại, lưới poster | pull-to-refresh, infinite scroll |
| S5 | MovieDetail | poster, mô tả, thời lượng, độ tuổi, danh sách suất theo ngày | chọn ngày dạng chip |
| S6 | SeatMap | sơ đồ ghế, chú giải, legend giá, thanh tổng tiền | **màn quan trọng nhất** |
| S7 | Checkout | phim, suất, ghế, tổng tiền, email nhận vé, nút thanh toán | **countdown 10:00** + microcopy US-08b |
| S8 | PaymentWebView | VNPay | khoá nút back, có nút "Hủy giao dịch" |
| S9 | PaymentResult | trạng thái + CTA | 4 nhánh |
| S10 | RefundNotice | lời xin lỗi, mã đơn, SLA hoàn tiền, liên hệ | không cho back về giỏ |
| S11 | HoldConflict | ghế vừa mất, highlight đúng ghế đó, sơ đồ tự refresh | [Chọn lại ghế] · [Suất khác] — **không** cho thanh toán |
| S12 | Banner contended | suất > 70% ghế `HELD` | "Đang có nhiều người chọn suất này — ghế có thể đổi bất cứ lúc nào." |
| S13 | SoldOut | 0 ghế AVAILABLE + 3 suất gần nhất còn ghế | [Báo tôi khi có ghế] · [Suất khác] |
| S14 | HoldExpiring | countdown vàng khi còn ≤ 2:00 | [Thanh toán ngay] · [Nhả ghế] |
| S11 | TicketList | đơn + trạng thái | chia tab: Sắp tới / Đã dùng / Đã hủy |
| S12 | TicketDetail | QR, mã chữ, thông tin | tăng sáng màn hình |
| S13 | Profile | tên, email, SĐT | |
| S14 | Notifications | danh sách thông báo | bản 1: chỉ đơn hàng |

### 3.3 Bốn trạng thái bắt buộc cho mỗi màn hình dữ liệu

| Trạng thái | Hiển thị | Ví dụ |
|---|---|---|
| Loading | skeleton (không spinner toàn màn) | S4 lưới poster xám |
| Empty | hình + 1 câu + 1 CTA | "Chưa có phim đang chiếu" |
| Error | lý do ngắn + nút Thử lại | "Không kết nối được. Kiểm tra mạng và thử lại." |
| Offline | banner trên cùng | "Đang xem dữ liệu đã lưu" |

### 3.4 SeatMap — chi tiết

| Thành phần | Quy tắc |
|---|---|
| Ghế AVAILABLE | viền đậm, nền trắng |
| Ghế đang chọn | nền màu chính, có số ghế |
| Ghế SOLD | mờ 40%, không bấm |
| Ghế BLOCKED / lối đi | không render như ghế |
| Ghế đôi (SWEETBOX) | hiển thị thành 1 khối 2 chỗ, chọn là chọn cả cặp |
| Chú giải giá | 3 mức giá tối đa, kèm màu |
| Zoom | pinch-to-zoom + double-tap, giữ vị trí khi xoay màn hình |
| Thanh dưới | "3 ghế · 240.000đ" + nút Tiếp tục (disable khi 0 ghế) |
| Quá 8 ghế | snackbar, không cho chọn thêm |
| Mất mạng khi mở | hiện cache gần nhất + banner, chặn nút Tiếp tục |

**Có đồng hồ đếm ngược.** Ghế được giữ 10 phút kể từ lúc bấm "Tiếp tục" (`holdExpiresAt` từ server). App đếm theo mốc server trả về, hiệu chỉnh bằng `secondsRemaining` ở mỗi lần poll — **không** tự tính từ giờ máy, vì đồng hồ máy khách thường lệch. Hết giờ: thoát S7, hiện "Đã hết thời gian giữ ghế" + quay về SeatMap (S13 nếu hết ghế).

### 3.5 Microcopy quan trọng (chép nguyên vào app)

| Chỗ | Câu |
|---|---|
| Trước thanh toán | "Ghế chỉ được xác nhận sau khi thanh toán thành công." |
| Ghế vừa mất | "Ghế F7 vừa được người khác chọn. Vui lòng chọn ghế khác." |
| Đã trả tiền, mất ghế | "Rất tiếc, ghế đã được người khác mua trước khi thanh toán hoàn tất. Chúng tôi sẽ hoàn tiền trong 24 giờ." |
| Đang xác nhận | "Đang xác nhận thanh toán. Vui lòng không đóng ứng dụng." |
| Hết hạn đơn | "Đơn đã hết hạn do chưa thanh toán." |

---

## 4. Technical design

### 4.1 Stack (đúng §15.1 của tài liệu gốc)

| Thành phần | Chọn | Ghi chú |
|---|---|---|
| Ngôn ngữ | Kotlin | |
| UI | Jetpack Compose + Material 3 | |
| Kiến trúc | MVVM + unidirectional data flow | |
| DI | Hilt | không `new` service thủ công |
| Async | Coroutines + Flow/StateFlow | |
| Network | Retrofit + OkHttp + kotlinx.serialization | interceptor cho token |
| Local | Room (cache phim/suất chiếu) + DataStore (cờ) | |
| Ảnh | Coil | |
| Paging | Paging 3 | danh sách phim + lịch sử |
| WebView | AndroidX WebKit | cho VNPay |
| Test | JUnit5 + MockK + Turbine + Compose UI Test | |

### 4.2 Phân lớp

```
ui/         Compose screen + ViewModel (chỉ StateFlow, không gọi Retrofit)
domain/     use case + model thuần Kotlin (không import Android)
data/
  ├── remote/  ApiService, Dto, mapper
  ├── local/   Room dao, entity, cache policy
  └── repo/    RepositoryImpl = remote + local, là nơi duy nhất quyết định fallback
di/         Hilt module
```

**Luật cứng:**
- Composable **không** gọi repository, chỉ đọc `uiState` và gửi event xuống ViewModel.
- ViewModel **không** giữ `Context`.
- Không có network/database trên main thread (ktor/Retrofit tự lo, nhưng Room phải dùng `suspend`).

### 4.3 State management

```kotlin
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T, val stale: Boolean = false) : UiState<T>
    data class Error(val message: String, val retryable: Boolean = true) : UiState<Nothing>
}
```

Quy ước:
- Mỗi màn hình 1 `ViewModel` + 1 `UiState` (không dùng nhiều `StateFlow` rời rạc cho cùng màn).
- Event một lần (snackbar, điều hướng) dùng `Channel`, không dùng `StateFlow`.
- `SavedStateHandle` cho: showtimeId, seatIds đã chọn, bookingId đang thanh toán → sống sót khi process bị kill.

### 4.4 Auth & token

| Việc | Cách làm |
|---|---|
| Lưu token | `EncryptedSharedPreferences` (hoặc DataStore + Keystore) — **không** lưu plain |
| Access token | gắn vào `Authorization: Bearer` qua OkHttp interceptor |
| Refresh | `Authenticator` của OkHttp: gặp 401 → gọi `/auth/refresh` **một lần** (dùng `Mutex` chống refresh song song) → retry request gốc |
| Refresh thất bại | xóa token, điều hướng về Login, xóa stack |
| Logout | gọi API + xóa token + xóa Room cache |
| Đổi mật khẩu | backend revoke các refresh token khác → app buộc đăng nhập lại |

### 4.5 Local storage & offline

| Dữ liệu | Lưu? | TTL | Ghi chú |
|---|---|---|---|
| Danh sách phim, chi tiết phim | Room | 6 giờ | hiện `stale` banner khi quá TTL |
| Suất chiếu | Room | 15 phút | thay đổi thường xuyên |
| Sơ đồ ghế | **Không cache** | — | phải realtime; mất mạng = chặn đặt vé |
| Booking, vé | Room (chỉ đọc) | 5 phút | để mở vé khi sóng yếu tại rạp |
| Token, profile | Encrypted prefs | — | xóa khi logout |

**Nguyên tắc:** cache **không bao giờ** được dùng để quyết định giá/ghế. Nếu cache nói ghế trống mà server nói đã bán → server thắng.

### 4.6 Network behavior

| Tình huống | Hành vi |
|---|---|
| Timeout (15s connect, 20s read) | hiện lỗi + Thử lại; **không** tự retry với POST |
| Retry tự động | chỉ với GET, tối đa 2 lần, backoff 1s/3s |
| POST tạo booking | gắn header `Idempotency-Key` (UUID sinh 1 lần cho mỗi lần bấm) để tránh tạo 2 đơn khi mạng chập chờn |
| Mất mạng giữa thanh toán | giữ nguyên `bookingId` trong `SavedStateHandle`; khi có mạng lại → `GET /bookings/{id}` trước khi cho bấm lại |
| 409 SEAT_CONFLICT | quay về SeatMap, tô đỏ ghế trong `conflicts[]`, giữ các ghế còn lại |
| 5xx | "Hệ thống đang bận", cho thử lại, **không** mất ghế đã chọn trên UI |
| Offline hoàn toàn | chỉ xem phim/suất chiếu đã cache; nút đặt vé disable kèm chú thích |

### 4.7 App lifecycle

| Tình huống | Xử lý |
|---|---|
| Vào background khi đang ở WebView | **Không** hủy WebView; giữ trạng thái; khi quay lại thì reload URL return (nếu đã redirect) |
| Process bị kill khi đang thanh toán | Khôi phục từ `SavedStateHandle`: bookingId → mở PaymentResult với trạng thái PROCESSING → poll |
| Xoay màn hình | ViewModel giữ state; SeatMap giữ vị trí zoom/scroll |
| Cuộc gọi đến / đa nhiệm | không xử lý đặc biệt, không được mất `bookingId` |
| Quay lại từ VNPay bằng deep link | `ceta://payment/result?bookingId=` → mở PaymentResult, **luôn** verify bằng API |
| Token hết hạn giữa phiên | refresh im lặng; nếu thất bại → Login, giữ `SavedStateHandle` để quay lại sau |
| Không có mạng khi mở app | vào Home với cache; không chặn app |

**Tuyệt đối không** kết luận thanh toán thành công dựa trên URL return hay tham số trong deep link — chỉ dựa trên `GET /bookings/{id}`.

### 4.8 Deep links

| Link | Đích |
|---|---|
| `ceta://payment/result?bookingId={id}` | S9 PaymentResult |
| `ceta://movie/{id}` | S5 |
| `https://<domain>/t/{ticketCode}` | S12 nếu đã cài app, ngược lại mở web |

---

## 5. API & dữ liệu

### 5.1 Base
`https://<domain>/api/v1` · JSON · thời gian ISO-8601 UTC · tiền là số nguyên VND.

### 5.2 Endpoint app dùng

| Method | Path | Dùng ở |
|---|---|---|
| POST | `/auth/register` | S3 |
| POST | `/auth/login` | S2 |
| POST | `/auth/refresh` | Authenticator |
| POST | `/auth/logout` | Profile |
| GET | `/auth/me` · PATCH `/me` · PUT `/me/password` | S13 |
| POST | `/auth/forgot-password` | S2 |
| GET | `/movies?q=&genre=&status=&page=` | S4 |
| GET | `/movies/{id}` | S5 |
| GET | `/showtimes?movieId=&date=` | S5 |
| GET | `/showtimes/{id}/seats` | S6 |
| POST | `/bookings` | S7 → tạo booking + nhận `paymentUrl` |
| GET | `/bookings/{id}` | S9 (nguồn sự thật) |
| GET | `/bookings?page=` | S11 |
| POST | `/bookings/{id}/cancel` | S11 |
| GET | `/tickets` · `/tickets/{id}` · `/tickets/{id}/qr` | S11, S12 |
| GET | `/notifications` | S14 |

### 5.3 Mẫu request/response

**POST /bookings**
```json
{ "showtimeId": 12, "seatIds": [45, 46], "paymentProvider": "VN_PAY" }
```
```json
{
  "bookingId": 9001,
  "status": "PENDING",
  "totalAmount": 240000,
  "expiresAt": "2026-11-14T12:45:00Z",
  "paymentUrl": "https://sandbox.vnpayment.vn/...",
  "tickets": null
}
```

**GET /bookings/9001** (nguồn sự thật cho S9)
```json
{
  "id": 9001,
  "status": "PAID",
  "refundRequired": false,
  "totalAmount": 240000,
  "movieTitle": "Phim A",
  "roomName": "Phòng 1",
  "startTime": "2026-11-14T13:00:00Z",
  "seats": ["F7", "F8"],
  "tickets": [{ "id": 1, "code": "TCK-...", "seatLabel": "F7" }]
}
```

**409 khi tạo booking**
```json
{
  "code": "SEAT_CONFLICT",
  "title": "Seat not available",
  "status": 409,
  "conflicts": [{ "seatId": 45, "label": "F7", "status": "SOLD" }]
}
```

### 5.4 Bảng trạng thái booking → UI

| status backend | refundRequired | Màn hình | CTA |
|---|---|---|---|
| PENDING | false | S9 "Đang xác nhận" | poll 3×2s, sau đó Thử lại |
| PAID | false | S9 thành công → S12 | Xem vé |
| CANCELLED | false | S9 thất bại | Chọn ghế khác |
| EXPIRED | false | S9 hết hạn | Đặt lại |
| CANCELLED | **true** | **S10 RefundNotice** | Liên hệ hỗ trợ |

### 5.5 Mã lỗi app phải xử lý riêng

| code | Hành vi |
|---|---|
| `SEAT_CONFLICT` | quay S6, tô đỏ `conflicts[]` |
| `MAX_SEATS_EXCEEDED` | snackbar |
| `SHOWTIME_CLOSED` | S5, disable suất đó |
| `BOOKING_NOT_CANCELLABLE` | ẩn nút Hủy + chú thích |
| `TICKET_ALREADY_USED` | ẩn ở app khách (chỉ staff thấy) |
| `UNAUTHORIZED` | refresh → thất bại thì Login |

---

## 6. Năm chi tiết bắt buộc của mobile

### 6.1 Quyền (permissions)

| Quyền | Có? | Vì sao |
|---|---|---|
| `INTERNET` | ✅ bắt buộc | Toàn bộ app |
| `POST_NOTIFICATIONS` | ✅ (Android 13+) | Nhắc suất chiếu + thông báo đơn hoàn tiền. **Xin khi** người dùng bật thông báo trong Tài khoản, không xin lúc mở app |
| `ACCESS_NETWORK_STATE` | ✅ | Phát hiện offline để hiện banner |
| Camera | ❌ | Khách **không** quét QR; việc soát vé làm ở web cho nhân viên |
| Vị trí | ❌ | Không có tính năng nào cần; đừng xin "để sau này" |
| Microphone | ❌ | Không có |
| Danh bạ / ảnh | ❌ | Không upload ảnh ở bản 1 |

**Nguyên tắc:** không xin quyền nào chưa dùng. Mỗi quyền thừa là một lý do để bị từ chối cài đặt.

### 6.2 Thiết bị hỗ trợ

| Mục | Chọn | Lý do |
|---|---|---|
| minSdk | **26** (Android 8.0) | WebView hiện đại, TLS 1.2+, không phải hỗ trợ máy quá cũ |
| targetSdk | bản mới nhất mà Google Play yêu cầu tại thời điểm phát hành | kiểm tra trong Play Console trước mỗi release |
| Màn hình | 320dp → 600dp+ | SeatMap phải pinch-zoom; test 5.0" và 6.7" |
| RAM thấp | phải chạy được trên 2 GB | không giữ nhiều bitmap poster |
| Kiến trúc CPU | arm64-v8a + armeabi-v7a | |
| Máy không có Google Play Services | vẫn dùng được (không phụ thuộc FCM ở bản 1) | |

### 6.3 Dữ liệu thu thập & xử lý

| Dữ liệu | Thu? | Lưu ở đâu | Xóa khi nào |
|---|---|---|---|
| Email, mật khẩu | ✅ | server (hash BCrypt); máy chỉ giữ email | xóa tài khoản |
| Họ tên, SĐT | ✅ (để liên hệ khi sự cố) | server | xóa tài khoản |
| Lịch sử đặt vé | ✅ | server | theo chính sách lưu trữ |
| Token | ✅ | EncryptedSharedPreferences | logout / hết hạn |
| Vị trí, danh bạ, ảnh | ❌ | — | — |
| Dữ liệu thẻ | ❌ | **không bao giờ** chạm app; VNPay xử lý trong WebView | — |
| Analytics/crash | ✅ (tối thiểu) | dịch vụ crash reporting | 90 ngày |
| Quảng cáo / tracking bên thứ ba | ❌ | — | — |

**Bắt buộc trước launch:** màn hình Tài khoản có "Xóa tài khoản" thật (gọi API), và chính sách dữ liệu hiển thị được từ trong app (link tới web).

---

## 7. Câu hỏi mở

| # | Câu hỏi | Ảnh hưởng |
|---|---|---|
| Q-1 | Ghế đôi bắt buộc mua cặp? | Đổi UI SeatMap (chọn 1 = chọn 2) |
| Q-2 | Có bán vé tại quầy? | App có cần chế độ nhân viên không (khuyến nghị: không, làm web) |
| Q-3 | Bắt buộc verify email? | Chặn đăng nhập hay chỉ nhắc |
| Q-4 | Có cần hóa đơn trong app? | Thêm form, đổi luồng sau thanh toán |
| Q-5 | Ngôn ngữ thứ hai (Anh)? | Tách strings.xml ngay từ đầu nếu có ý định |
