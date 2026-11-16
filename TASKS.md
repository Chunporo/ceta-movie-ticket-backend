# TASKS — bảng điều khiển cho Codex

> Cách dùng: mỗi phiên chỉ làm **một** task. Nói với Codex: `Đọc prompts/T3.md và làm.`
> Xong task nào thì tick ô, commit, rồi mới sang task tiếp. Không làm 2 task một phiên.

| # | Task | Prompt | Điều kiện xong (gate) | Xong |
|---|---|---|---|---|
| T0 | Bỏ tầng hold cũ, build xanh | `prompts/T0-green-build.md` | `mvn -q clean test` xanh | [x] |
| T1 | Flyway `V1` + entity | `prompts/T1-migration.md` | App khởi động được với `ddl-auto=validate` | [x] |
| T2 | `InventoryService` + ArchUnit | `prompts/T2-inventory.md` | 3 unit test + ArchUnit xanh | [ ] |
| **T3** | **Test đồng thời 50 thread** | `prompts/T3-concurrency.md` | **Đúng 1 thắng, 49 nhận 409** ← **Gate A** | [ ] |
| T4 | `POST /bookings` | `prompts/T4-create-booking.md` | 409 có `details[]`; ≤8 ghế; 1 PENDING/user/suất | [ ] |
| T5 | `DELETE` + `GET /bookings/{id}` | `prompts/T5-release.md` | Nhả ghế < 1s; 403 khi không phải owner | [ ] |
| T6 | VNPay IPN + return | `prompts/T6-payment.md` | Gọi 5 lần → 1 vé, 1 lần `processed_at` | [ ] |
| T7 | `refund_queue` | `prompts/T7-refund.md` | IPN muộn → 1 dòng refund, 0 vé | [ ] |
| T8 | Sweeper + kill switch | `prompts/T8-sweeper.md` | Cả 2 chế độ (on/off) xanh | [ ] |
| T9 | Siết phân quyền | `prompts/T9-authz.md` | 403 đúng nhóm; không còn fallback ADMIN | [ ] |
| T10 | Admin: soát QR + refund queue | *(viết sau khi T9 xong)* | Nhân viên soát được vé trên điện thoại | [ ] |

## Thứ tự bắt buộc

```
T0 → T1 → T2 → T3 ⛔GateA → T4 → T5 → T6 → T7 → T8 → T9 → T10
```

**Không nhảy cóc.** T3 là chốt chặn: nếu 50 thread không cho ra đúng 1 thắng thì mọi thứ sau đó vô nghĩa.

## Cần có trước khi bắt đầu

| Thứ | Trạng thái |
|---|---|
| Java 21 + Maven | `mvn -v` chạy được |
| MySQL 8 local (cho T3) | `docker run -d --name ceta-mysql -p 3306:3306 -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=ceta mysql:8` |
| `IT_MYSQL_URL` | `jdbc:mysql://localhost:3306/ceta` |
| `docs/BASELINE.md` + `AGENTS.md` | đã có trong repo |
| Tài khoản VNPay sandbox | cho T6 ở mức thật; chưa có thì mock `VnPayClient` |

## Commit message cho từng task

```
T0  chore(baseline): remove broken seat-hold layer, green build
T1  feat(db): add flyway baseline migration and inventory entities
T2  feat(inventory): atomic seat claim with archunit guard
T3  test(inventory): concurrent claim of a single seat
T4  feat(booking): create booking claims seats atomically
T5  feat(booking): release seats on cancel, get booking detail
T6  feat(payment): vnpay ipn and return with idempotency
T7  feat(payment): refund queue for late payments
T8  feat(inventory): expiry sweeper and hold kill switch
T9  feat(security): per-endpoint authorization
T10 feat(admin): qr check-in and refund queue screens
```
