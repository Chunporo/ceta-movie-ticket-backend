# CETA Android — Test Plan, Release Guide & README

> ⚠️ **BASELINE v1.0 — 16/11/2026.** Có giữ ghế 10 phút — TC-18/21/25 đã sửa, thêm TC-31→34.

> Gộp 3 tài liệu: **Test plan + test cases** · **Release & deployment guide** · **README / developer setup**
> Đọc kèm `CETA_Android_Spec.md` (PRD, UI, technical design).

| | |
|---|---|
| Phiên bản | 1.0 — 2026-11-14 |
| Áp dụng cho | Android app của CETA (Kotlin + Compose) |

---

# Phần A — Test plan

## A.1 Phạm vi

| Trong phạm vi | Ngoài phạm vi |
|---|---|
| Luồng đặt vé từ đăng nhập → có vé | Web admin (test riêng) |
| Thanh toán VNPay sandbox + production nhỏ | Ứng dụng iOS (chưa có) |
| Trạng thái lỗi: mất mạng, ghế mất, đơn hết hạn | Load test backend (đã có ở backend plan) |
| QR hiển thị + soát vé (phần app) | Thiết bị POS tại quầy |
| Xoay màn hình, background, process death | Đa ngôn ngữ (bản 1 chỉ tiếng Việt) |

## A.2 Các tầng test

| Tầng | Công cụ | Mục tiêu số test | Chạy ở đâu |
|---|---|---|---|
| Unit (ViewModel, mapper, price format) | JUnit5 + MockK + Turbine | 30 | mỗi commit (CI) |
| Compose UI (state → hiển thị) | Compose UI Test | 10 | CI |
| Integration (Retrofit + MockWebServer) | MockWebServer | 8 | CI |
| Manual trên máy thật | — | 30 case (§A.4) | trước mỗi release |

## A.3 Ma trận thiết bị (tối thiểu)

| Thiết bị | Android | Vì sao |
|---|---|---|
| Pixel 6 (hoặc emulator cùng cấu hình) | bản mới nhất | targetSdk hiện tại |
| Máy tầm trung 6.5" (Snapdragon 6xx, 4 GB) | 13 | đại diện số đông |
| Máy cũ / RAM 2 GB | 8.0 (minSdk) | kiểm tra tụt hiệu năng + WebView cũ |
| Máy màn nhỏ 5.0" | 10 | SeatMap có bị tràn không |
| Tablet 10" | 12 | layout mở rộng |

## A.4 Test cases

Ký hiệu ưu tiên: **P0** = chặn release · P1 = phải xanh trước production · P2 = nên có.

### A.4.1 Đăng nhập & tài khoản

| ID | P | Điều kiện | Bước | Kết quả mong đợi |
|---|---|---|---|---|
| TC-01 | P0 | Email đã đăng ký | Nhập đúng email + mật khẩu → Đăng nhập | Vào Home; token được lưu; mở lại app vẫn đăng nhập |
| TC-02 | P0 | — | Nhập sai mật khẩu 1 lần | Lỗi hiện dưới ô mật khẩu; vẫn cho thử lại; **không** xóa email đã nhập |
| TC-03 | P1 | — | Sai 5 lần liên tiếp | Hiện thông báo chờ 1 phút; nút đăng nhập disable |
| TC-04 | P1 | Email mới | Đăng ký với mật khẩu 6 ký tự | Báo "Mật khẩu tối thiểu 8 ký tự"; không gọi API |
| TC-05 | P1 | — | Quên mật khẩu với email không tồn tại | Vẫn hiện "Nếu email tồn tại…" (không lộ) |
| TC-06 | P1 | Đã đăng nhập | Đổi mật khẩu | Bị đăng xuất, phải đăng nhập lại bằng mật khẩu mới |

### A.4.2 Duyệt phim & suất chiếu

| ID | P | Điều kiện | Bước | Kết quả mong đợi |
|---|---|---|---|---|
| TC-07 | P0 | Có mạng | Mở tab Phim | Danh sách hiện, có skeleton khi tải, không nháy trắng |
| TC-08 | P1 | Đã tải 1 lần, sau đó bật máy bay | Mở lại tab Phim | Hiện dữ liệu cache + banner "Đang xem dữ liệu đã lưu" |
| TC-09 | P1 | — | Tìm "av" | Lọc đúng; xóa từ khóa trả về đủ danh sách |
| TC-10 | P1 | — | Chọn phim → chọn ngày khác | Danh sách suất đổi theo ngày; suất đã qua hiển thị mờ |
| TC-11 | P2 | Không có suất nào trong ngày | Chọn ngày đó | Empty state "Ngày này chưa có suất chiếu" + gợi ý ngày khác |

### A.4.3 Chọn ghế (màn quan trọng nhất)

| ID | P | Điều kiện | Bước | Kết quả mong đợi |
|---|---|---|---|---|
| TC-12 | P0 | Suất còn ghế | Mở SeatMap | Ghế SOLD mờ + không bấm; BLOCKED không hiện; tổng tiền = 0 khi chưa chọn |
| TC-13 | P0 | — | Chọn 3 ghế | Thanh dưới hiện "3 ghế · <tổng>"; tổng = Σ giá từng ghế |
| TC-14 | P1 | — | Chọn ghế thứ 9 | Bị chặn + snackbar "Tối đa 8 ghế mỗi đơn"; 8 ghế đầu vẫn giữ |
| TC-15 | P1 | — | Pinch-zoom + xoay màn hình | Vị trí và ghế đã chọn được giữ nguyên |
| TC-16 | P1 | Ghế đôi | Chọn 1 nửa ghế đôi | Tự chọn cả cặp; tổng tiền tính 2 ghế (nếu Q-1 = bắt buộc cặp) |
| TC-17 | P0 | Bật máy bay khi đang ở SeatMap | Bấm Tiếp tục | Bị chặn + "Cần kết nối mạng để đặt vé"; không crash |

### A.4.4 Thanh toán — nhóm rủi ro cao nhất

| ID | P | Điều kiện | Bước | Kết quả mong đợi |
|---|---|---|---|---|
| TC-18 | P0 | — | Bấm Tiếp tục | Tạo đơn thành công, countdown **10:00** hiện ở S7 và giảm đúng; đóng app 30s rồi mở lại → countdown vẫn khớp `holdExpiresAt` (không reset) |
| TC-19 | P0 | VNPay sandbox, thẻ test thành công | Thanh toán thành công → trở về app | App gọi `GET /bookings/{id}`, hiện "Đang xác nhận…" rồi **màn thành công + vé** |
| TC-20 | P0 | — | Bấm "Thanh toán" rồi hủy ở VNPay | Về app hiện "Thanh toán chưa hoàn tất"; ghế **vẫn chọn được lại**; không có vé |
| TC-21 | P0 | 2 máy cùng chọn ghế F7 | Máy A bấm Tiếp tục trước, máy B bấm sau | Máy B nhận **409 → S11**: highlight đúng F7, các ghế khác của B vẫn hiện, **không** sang thanh toán. Máy A vào VNPay bình thường |
| TC-22 | P0 | Đang ở WebView | Bấm back của hệ thống | Hỏi "Hủy giao dịch?" — không thoát im lặng |
| TC-23 | P0 | Đang ở WebView | Kill app (vuốt khỏi recent) rồi mở lại | Vào PaymentResult với trạng thái PROCESSING, poll và hiện đúng kết quả thật |
| TC-24 | P1 | — | Mất mạng đúng lúc bấm Thanh toán | Không tạo đơn trùng khi thử lại (kiểm tra `Idempotency-Key`); hiện lỗi rõ ràng |
| TC-25 | P1 | Đơn PENDING quá 10 phút | Mở lại đơn trên máy khác | Hiện "Đã hết thời gian giữ ghế"; ghế đã về AVAILABLE (kiểm tra bằng máy thứ 2 chọn lại được); không cho thanh toán tiếp |
| TC-26 | P1 | — | Bấm Thanh toán 2 lần thật nhanh | Chỉ 1 WebView được mở; không tạo 2 đơn |

### A.4.5 Vé & soát vé

| ID | P | Điều kiện | Bước | Kết quả mong đợi |
|---|---|---|---|---|
| TC-27 | P0 | Có vé PAID | Mở TicketDetail | QR hiển thị, màn hình tự tăng sáng; có mã chữ dự phòng |
| TC-28 | P0 | Vé đã soát (staff quét trên web) | Mở lại vé | Hiện nhãn "Đã sử dụng" + thời điểm |
| TC-29 | P1 | Sóng yếu tại rạp | Mở vé từ cache | Vé hiện được (cache 5 phút); nếu đã hết cache → thông báo rõ, không hiện QR sai |
| TC-30 | P1 | Còn 30 phút trước giờ chiếu | Bấm Hủy vé | Bị chặn + "Chỉ hủy được trước 60 phút" (khớp BR-03) |
| TC-31 | P1 | Đang ở S7 có countdown | Bấm back | Ghế về AVAILABLE **trong ≤ 20s** (kiểm tra bằng máy thứ 2, hoặc query DB); booking CANCELLED |
| TC-32 | P1 | Đang ở S7 | Chuyển app ra background 2 phút rồi quay lại | Ghế đã được nhả; app hiện "Phiên giữ ghế đã kết thúc", không cho thanh toán tiếp |
| TC-33 | P1 | Đang ở S7 | Chờ hết 10 phút | Countdown về 0 → hiện "Đã hết thời gian giữ ghế" + quay về SeatMap; nút thanh toán disable ngay khi còn 0:00 |
| TC-34 | P1 | Đã có 1 đơn PENDING cho suất này | Chọn ghế khác cùng suất, bấm Tiếp tục | Bị chặn + "Bạn đang có 1 đơn chờ thanh toán cho suất này" + nút [Quay lại đơn] (không tạo đơn thứ 2) |

## A.5 Điều kiện thoát (exit criteria)

Release chỉ được phát hành khi:

- [ ] 100% case **P0** xanh trên ít nhất 2 thiết bị thật
- [ ] ≥ 90% case **P1** xanh, các case đỏ có ghi chú chấp nhận
- [ ] Crash-free ≥ 99,5% trên internal testing 3 ngày
- [ ] Không có ANR trong 3 ngày internal testing
- [ ] Unit + UI test xanh trên CI
- [ ] Đã thử 1 giao dịch **tiền thật** (số tiền nhỏ) và đối soát khớp

## A.6 Smoke test sau mỗi release (5 phút)

Đăng nhập → chọn 1 suất → chọn 1 ghế → thanh toán thật 1.000đ → nhận vé → mở QR. Nếu bước nào fail, halt rollout ngay.

---

# Phần B — Release & deployment guide

## B.1 Build types

| Build | applicationId | Mục đích | Ký |
|---|---|---|---|
| `debug` | `vn.ceta.app.debug` | dev, log đầy đủ | debug key |
| `staging` | `vn.ceta.app.staging` | test với backend staging, VNPay sandbox | keystore riêng |
| `release` | `vn.ceta.app` | production, VNPay thật | upload key + Play App Signing |

**Quan trọng:** dùng `applicationId` riêng cho staging để cài song song trên cùng máy — tránh ghi đè app production khi đang test.

## B.2 Cấu hình môi trường

```kotlin
// Không hard-code URL. Dùng BuildConfig từ build.gradle.kts + secrets ở CI.
buildConfigField("String", "API_BASE_URL", "\"https://api.ceta.vn/api/v1\"")
buildConfigField("String", "VNPAY_RETURN_DEEP_LINK", "\"ceta://payment/result\"")
```
| Secret | Lưu ở đâu |
|---|---|
| URL backend | `buildConfigField` theo flavour |
| API key crash reporting | CI secret, **không** commit |
| Keystore | CI secret / máy cá nhân, **không** commit (thêm `*.jks` vào `.gitignore`) |

## B.3 Ký app

1. Tạo keystore **một lần**, lưu 2 nơi (máy + cloud private). Mất keystore = không update được app.
2. Bật **Play App Signing** để Google giữ signing key gốc.
3. `key.properties` chỉ ở máy local/CI:

```
storeFile=../keystore/ceta-upload.jks
storePassword=***
keyAlias=ceta-upload
keyPassword=***
```

4. Không bao giờ commit `key.properties` hay `*.jks`.

## B.4 Versioning

| Trường | Quy tắc | Ví dụ |
|---|---|---|
| `versionCode` | số nguyên, **luôn tăng**, không bao giờ tái sử dụng | `1` → `2` → `3` |
| `versionName` | `MAJOR.MINOR.PATCH` theo ngữ nghĩa | `1.0.0` → `1.0.1` (fix) → `1.1.0` (tính năng) |

Quy ước: mỗi lần upload lên Play là +1 `versionCode`, kể cả bản nội bộ.

## B.5 Quy trình phát hành

| Bước | Việc | Thời gian |
|---|---|---|
| 1 | Tạo nhánh `release/x.y.z`, chạy full test, bump version | 0,5 ngày |
| 2 | Build AAB signed, upload **Internal testing** | 30 phút |
| 3 | Tự cài + smoke test §A.6 trên 2 máy thật | 1 giờ |
| 4 | Mời 3–5 người **Closed testing**, chạy 3 ngày | 3 ngày |
| 5 | Xem crash/ANR, sửa nếu cần (quay lại bước 2) | — |
| 6 | **Production, staged rollout 10%** | 1 ngày |
| 7 | Theo dõi crash 24h → tăng 50% → 100% | 2 ngày |

**Không bao giờ** rollout 100% ngay. Không phát hành chiều thứ 6 hoặc trước suất chiếu cao điểm.

## B.6 Rollback

| Tình huống | Cách xử lý |
|---|---|
| Crash tăng đột biến sau rollout | **Halt rollout** trong Play Console (dừng phát hành bản mới cho người chưa cập nhật) |
| Bản mới hỏng nặng | Phát hành bản vá từ commit trước, `versionCode` +1 — **không** hạ versionCode (Play không cho) |
| Backend đổi API không tương thích | **Bắt buộc**: backend giữ endpoint cũ ít nhất 1 phiên bản app; nếu phải phá, dùng force-update screen |
| Người dùng đã cập nhật bản lỗi | Không thu hồi được; phải có server-side kill switch (feature flag) cho tính năng hỏng |

**Bài học bắt buộc:** mọi thay đổi phá vỡ API phải đi kèm **feature flag phía server**, vì app đã cài trên máy khách không thể rollback.

## B.7 Checklist trước khi bấm "Start rollout"

- [ ] `versionCode` lớn hơn bản trên Play
- [ ] Đã test trên minSdk 26 và bản Android mới nhất
- [ ] Deep link `ceta://payment/result` hoạt động ở bản release (test bằng `adb shell am start`)
- [ ] Return URL của VNPay trỏ đúng domain production (không phải localhost)
- [ ] Không còn log nhạy cảm (token, payload thanh toán)
- [ ] `isMinifyEnabled = true` + mapping file đã upload để đọc crash stack
- [ ] Chính sách dữ liệu + điều khoản hiển thị được trong app
- [ ] Màn "Xóa tài khoản" hoạt động thật

## B.8 Sau khi phát hành (24h đầu)

| Việc | Ngưỡng |
|---|---|
| Crash-free sessions | ≥ 99,5% — dưới thì halt |
| ANR | ≤ 0,5% |
| Tỷ lệ bỏ dở thanh toán | ≤ 20% |
| Ca REFUND_PENDING | đếm và đối chiếu refund queue |

---

# Phần C — README / developer setup

## C.1 Yêu cầu

| Công cụ | Phiên bản |
|---|---|
| Android Studio | bản ổn định mới nhất |
| JDK | 17+ (dùng JDK bundled) |
| Android SDK | compileSdk theo bản mới nhất đã cài |
| Gradle | dùng wrapper trong repo, không cài riêng |
| Backend chạy local | xem `CETA_Backend_Architecture.md` §12 |

## C.2 Chạy lần đầu (5 lệnh)

```bash
git clone <repo-url> ceta-android && cd ceta-android
cp local.properties.example local.properties      # sdk.dir + api url
./gradlew assembleDebug                            # build
./gradlew installDebug                             # cài lên máy/emulator
./gradlew testDebugUnitTest                        # chạy unit test
```

Nếu chạy với backend local, trỏ `API_BASE_URL` về IP LAN của máy dev (không dùng `localhost` — emulator không thấy host):

```
http://10.0.2.2:8080/api/v1     # Android emulator → host
http://<LAN-IP>:8080/api/v1     # máy thật cùng wifi
```

## C.3 Cấu trúc thư mục

```
app/src/main/java/vn/ceta/app/
├── ui/            screen/, component/, theme/, navigation/
├── domain/        model/, usecase/
├── data/
│   ├── remote/    api/, dto/, mapper/, interceptor/
│   ├── local/     db/, dao/, entity/, prefs/
│   └── repository/
├── di/            NetworkModule, DatabaseModule, RepositoryModule
└── util/          Result, DateFormatter, MoneyFormatter
```

## C.4 Quy ước code (bắt buộc)

| Quy ước | Lý do |
|---|---|
| Mỗi màn = 1 `Screen` + 1 `ViewModel` + 1 `UiState` | dễ test, dễ review |
| Không gọi API trong Composable | recomposition sẽ gọi lại |
| Tiền dùng `Long` VND, format qua `MoneyFormatter` | tránh lỗi dấu phẩy/float |
| Thời gian lưu UTC, hiển thị theo `Asia/Ho_Chi_Minh` | tránh lệch múi giờ suất chiếu |
| Mọi string hiển thị nằm trong `strings.xml` | chuẩn bị đa ngôn ngữ |
| Không log token / payload thanh toán | bảo mật |
| Chạy `./gradlew lint` trước khi push | CI sẽ chặn |

## C.5 Lệnh thường dùng

```bash
./gradlew testDebugUnitTest          # unit test
./gradlew connectedDebugAndroidTest  # UI test trên máy thật
./gradlew lint                       # lint
./gradlew assembleRelease            # build release (cần key.properties)
adb shell am start -a android.intent.action.VIEW -d "ceta://payment/result?bookingId=1"
```

## C.6 Xử lý sự cố thường gặp

| Triệu chứng | Nguyên nhân thường gặp | Cách sửa |
|---|---|---|
| App không gọi được API trên emulator | Dùng `localhost` | Đổi sang `10.0.2.2` |
| 401 liên tục | Refresh token hỏng / đồng hồ máy lệch | Xóa dữ liệu app, kiểm tra giờ hệ thống |
| WebView trắng sau thanh toán | Return URL sai scheme/deep link chưa khai báo | Kiểm tra `intent-filter` + `VNPAY_RETURN_DEEP_LINK` |
| Không thấy vé sau khi trả tiền | Chưa gọi `GET /bookings/{id}`, đang tin vào URL | Đúng thiết kế: chỉ dùng API làm nguồn sự thật |
| Build lỗi `Missing keystore` | Thiếu `key.properties` | Tạo lại file, không commit |

## C.7 Những gì KHÔNG được commit

`local.properties` · `key.properties` · `*.jks` · `google-services.json` (nếu có) · thư mục `build/` · file log.
