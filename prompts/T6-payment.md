# T6 — VNPay IPN + return (idempotent)

Đọc `docs/BASELINE.md` §6 F3, D-09 → D-12.

## Một use case dùng chung: `ApplyPaymentResultUseCase.apply(params)`

Thứ tự **bắt buộc**:

1. **Idempotency trước mọi thứ:**
   `UPDATE payment_transaction SET processed_at = NOW() WHERE provider='VNPAY' AND txn_ref=:ref AND processed_at IS NULL`
   — `affectedRows = 0` → `return OK` ngay (đã xử lý), không làm gì thêm.
2. Verify **đủ 3**: chữ ký HMAC-SHA512 · `vnp_Amount/100 == booking.totalAmount` · `vnp_TmnCode == config`.
   Sai một trong ba → `log.warn("SECURITY ...")`, trả lỗi, **không** đổi trạng thái gì.
3. `inventory.promoteToSold(bookingId)`. `affectedRows != sốGhế` → ghi `refund_queue(reason='LATE_PAYMENT')`, trả OK (không ném 500 — VNPay sẽ retry mãi).
4. `booking PENDING → PAID`, sinh `ticket` từ `booking_seat`, `assert Σ ticket.price == booking.totalAmount`.
5. Ghi `booking_status_history`.

## Endpoint

- `POST /api/v1/payments/vnpay/ipn` — `permitAll`, trả đúng format VNPay yêu cầu (`RspCode`, `Message`).
- `GET /api/v1/payments/vnpay/return` — `permitAll`, gọi **cùng** use case (cờ `app.payment.return-claim`), sau đó redirect về deep link app.

## Cấm

- Không tin `vnp_ResponseCode` một mình.
- Không sinh vé trước khi kiểm amount.
- Không `try/catch` nuốt exception rồi trả success.

## Xong khi

Test: cùng payload gọi 5 lần → **1 vé**, `processed_at` chỉ set 1 lần; sai chữ ký → 0 vé + không đổi status; sai amount → 0 vé.
`./scripts/verify.sh` PASS. Dán output.
