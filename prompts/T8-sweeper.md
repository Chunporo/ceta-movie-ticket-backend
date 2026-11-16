# T8 — Sweeper + kill switch

Đọc `docs/BASELINE.md` §6, D-06, D-08.

## Sweeper

`@Scheduled(fixedDelayString = "${app.booking.sweeper-interval}")` — mỗi lần chạy, trong **một** transaction, đúng 2 câu:

```sql
UPDATE showtime_seat SET status='AVAILABLE', held_until=NULL, booking_id=NULL
 WHERE status='HELD' AND held_until < NOW();

UPDATE booking SET status='EXPIRED'
 WHERE status='PENDING' AND hold_expires_at < NOW();
```

Ghi `booking_status_history` cho các booking vừa đổi. Trả về số dòng đã nhả (log).

**Không** đọc danh sách rồi update từng dòng — phải là 2 câu UPDATE điều kiện.

## Kill switch

`app.booking.hold-enabled=false`:
- `POST /bookings` **không** claim ghế (chỉ tạo PENDING).
- IPN claim từ `AVAILABLE` (nhánh cũ) thay vì promote từ `HELD`.
- Sweeper không đụng ghế.
Cùng một `ApplyPaymentResultUseCase`, chỉ khác nhánh claim.

## Xong khi

Test: tạo booking quá hạn → sweeper nhả ghế + `EXPIRED`; chạy sweeper 2 lần liên tiếp không lỗi; bật/tắt flag → cả 2 chế độ xanh (test lại T4 với flag off).
`./scripts/verify.sh` PASS. Dán output.
