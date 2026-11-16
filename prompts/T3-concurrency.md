# T3 — Test đồng thời 50 thread ⛔ GATE A

Đọc `AGENTS.md` §6. Task này **chỉ viết test**, không sửa code production. Nếu test đỏ vì code sai → báo lại, không tự sửa lung tung.

## 1. Profile `it-mysql` trong `pom.xml`

Profile bật `@Tag("it-mysql")` với datasource lấy từ `${IT_MYSQL_URL}`, `${IT_MYSQL_USER:root}`, `${IT_MYSQL_PASSWORD:root}`. Surefire tách `groups`.

## 2. `ConcurrentClaimTest`

`src/test/java/.../inventory/ConcurrentClaimTest.java`:

- `@SpringBootTest`, `@Tag("it-mysql")`, `@ActiveProfiles("it-mysql")`.
- `@BeforeEach`: reset `showtime_seat` — 1 suất, 10 ghế đều `AVAILABLE`.
- 50 thread, `CountDownLatch start` cho tất cả chờ rồi bắn cùng lúc:
  mỗi thread gọi `inventory.claim(showtimeId, List.of(seatIdF7), bookingIdRiêng, now+10m)`.
- `CountDownLatch done` để join hết.
- Assert:
  1. đúng **1** thread không ném exception;
  2. **49** thread ném `SeatConflictException`;
  3. `SELECT COUNT(*) FROM showtime_seat WHERE status='HELD'` = **1**;
  4. không có exception nào khác ngoài `SeatConflictException` (bắt và fail nếu có).

## 3. Chạy

```bash
docker run -d --name ceta-mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=ceta mysql:8
export IT_MYSQL_URL=jdbc:mysql://localhost:3306/ceta
mvn -q -Pit-mysql test -Dtest=ConcurrentClaimTest
```

## Xong khi

Test xanh **và** chạy 3 lần liên tiếp đều xanh (không flaky). Dán output cả 3 lần.
Nếu đỏ: dán nguyên stack trace, dừng lại, không tự đổi sang Redis hay bỏ `FOR UPDATE`.
