# T4 — `POST /api/v1/bookings` (claim ngay)

Đọc `docs/BASELINE.md` §6 F2, §7, các quyết định D-02, D-14, D-16, D-17, D-18.

## Luồng bắt buộc (một transaction)

1. Validate: 1..8 ghế; suất chiếu nằm trong cửa sổ mở bán T-14 và chưa bắt đầu; user chưa có booking `PENDING` cho suất này.
2. `inventory.claim(showtimeId, seatIds, bookingId, now+hold-ttl)` — **bookingId phải sinh trước** để ghi vào `showtime_seat.booking_id`.
3. Insert `booking(PENDING, total_amount, hold_expires_at)`, insert `booking_seat` (snapshot giá từng ghế).
4. `assert Σ booking_seat.price == total_amount`.
5. Response `{bookingId, holdExpiresAt, totalAmount, seats[]}`.

## Lỗi

| Tình huống | HTTP | code |
|---|---|---|
| Ghế vừa bị người khác giữ | 409 | `SEAT_CONFLICT` + `details[{seatId}]` |
| Đã có booking PENDING cho suất này | 409 | `BOOKING_ALREADY_PENDING` |
| Quá 8 ghế | 400 | `TOO_MANY_SEATS` |
| Ngoài cửa sổ bán | 400 | `OUTSIDE_SALE_WINDOW` |
| Ghế đôi thiếu cặp | 400 | `INVALID_COUPLE_SEAT` |

## Cấm

- Không gọi `pricingService.calculate()` sau bước 1 (giá chốt một lần — D-12).
- Không dùng Redis cho bất cứ bước nào ở đây.
- Không `try/catch` rồi trả 200 khi claim thất bại.

## Xong khi

Test: claim thành công; claim khi ghế đã HELD → 409 + `details`; ghế thứ 9 → 400; booking PENDING thứ 2 → 409.
`./scripts/verify.sh` PASS. Dán output.
