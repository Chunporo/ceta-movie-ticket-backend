# T10 — Soát vé (check-in) cho nhân viên

Đọc `docs/BASELINE.md` D-19, D-23 và `docs/CETA_Backend_Architecture.md` §8 (QR ký).

Nhân viên dùng web admin (không có role STAFF trong app — D-19), nên check-in là thao tác của user có role `ADMIN`.
Phần "refund queue" của web admin đã có API từ T7; task này chỉ làm phía soát vé.

## Migration `V3__ticket_checkin.sql`

`tickets` thêm `checked_in_at DATETIME(6) NULL`, `checked_in_by BIGINT NULL`. Không sửa `V1`.

## `POST /api/v1/admin/tickets/check-in`

Body `{ "qr": "..." }` **hoặc** `{ "code": "TCK-..." }` (nhập tay khi QR hỏng) — đúng một trong hai, nếu không → 400 `INVALID_REQUEST`.
`@PreAuthorize("hasRole('ADMIN')")`.

Thứ tự kiểm tra:

1. `qr` → `TicketQrService.verify`: sai chữ ký/định dạng → 400 `INVALID_QR`; hết hạn → 400 `QR_EXPIRED`. `code` → tra vé, không có → 404 `TICKET_NOT_FOUND`.
2. Booking của vé phải `PAID`, ngược lại → 409 `TICKET_NOT_VALID`.
3. Cửa sổ soát vé (cả hai đường): mở từ `app.ticket.checkin-opens-before` (mặc định 60m) trước giờ chiếu → trước đó 409 `CHECKIN_NOT_OPEN`; đóng sau giờ chiếu + 2h → 409 `CHECKIN_CLOSED`.
4. Đánh dấu nguyên tử: `UPDATE tickets SET checked_in_at=:now, checked_in_by=:staff WHERE id=:id AND checked_in_at IS NULL`.
   `affectedRows = 0` → 409 `ALREADY_CHECKED_IN`, `details[{checkedInAt}]`.
5. 200 `{ticketId, code, seatLabel, movieTitle, roomName, startTime, checkedInAt}` để nhân viên đối chiếu.

## Cấm

- Không check-in bằng đọc-rồi-ghi (race giữa 2 điện thoại cùng quét) — chỉ UPDATE có điều kiện.
- Không tin `qr` mà không verify; không trả vé của người khác ra endpoint công khai.

## Xong khi

Test: QR hợp lệ → 200; quét lần 2 → 409 `ALREADY_CHECKED_IN`; QR giả/hết hạn → 400; ngoài cửa sổ → 409; booking không `PAID` → 409; user thường → 403; 5 thread cùng quét 1 vé trên MySQL → đúng 1 thành công.
`./scripts/verify.sh` PASS.

## Chưa làm ở task này

Huỷ suất chiếu (D-22: `PAID → REFUNDED` hàng loạt + email/push + lý do bắt buộc) cần thêm trạng thái suất chiếu và lý do hoàn mới — để task sau.
