# T7 — `refund_queue` + nhánh thanh toán muộn

Đọc `docs/BASELINE.md` §4 (`refund_queue`), §5.

## Service

`RefundService.enqueue(bookingId, reason, amount)`: `INSERT ... ON DUPLICATE KEY UPDATE id=id` — `unique(booking_id)` bảo đảm không hoàn 2 lần.
`reason` ∈ `LATE_PAYMENT` | `DOUBLE_PAY` | `HOLD_EXPIRED_DURING_PAY`.

## API admin

- `GET /api/v1/admin/refunds?status=PENDING` — `@PreAuthorize("hasRole('ADMIN')")`
- `PUT /api/v1/admin/refunds/{id}/done` — body `{note}` → `status='DONE'`, `done_at=NOW()`
- `PUT /api/v1/admin/refunds/{id}/failed` — body `{note}`

## Xong khi

Test: seed booking `EXPIRED` (ghế đã nhả) rồi bắn IPN muộn → **1 dòng `refund_queue`**, **0 ticket**; bắn IPN lần 2 → vẫn 1 dòng; user thường gọi admin API → 403.
`./scripts/verify.sh` PASS. Dán output.
