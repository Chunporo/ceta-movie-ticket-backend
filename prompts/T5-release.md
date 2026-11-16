# T5 — `DELETE /bookings/{id}` + `GET /bookings/{id}`

Đọc `docs/BASELINE.md` §6 F4, F5, §7.

## `DELETE /api/v1/bookings/{id}`

- Chỉ owner (`@PreAuthorize` hoặc kiểm `booking.userId == currentUser.id` → 403).
- Chỉ khi `status = 'PENDING'` (ngược lại 409 `BOOKING_NOT_PENDING`).
- `inventory.release(bookingId)` + `booking → CANCELLED` + ghi `booking_status_history`, cùng transaction.
- Trả **204**.
- Gọi 2 lần: lần 2 trả 409 (không phải 500).

## `GET /api/v1/bookings/{id}`

Trả: `status`, `holdExpiresAt`, `secondsRemaining` (tính server-side, âm → 0), `totalAmount`, `seats[]`, `tickets[]`.
Không trả entity thẳng. Không trả `held_until` của ghế người khác.

## Xong khi

Test: release thành công → ghế về `AVAILABLE` và `booking_id IS NULL`; không phải owner → 403; booking PAID → 409; `secondsRemaining` giảm đúng sau 2 giây.
`./scripts/verify.sh` PASS. Dán output.
