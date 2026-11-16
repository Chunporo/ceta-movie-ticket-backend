# T1 — Flyway `V1__baseline.sql` + entity

Đọc `AGENTS.md` và `docs/BASELINE.md` §4, §5.

## 1. Sinh DDL cho các entity đã có

Thêm tạm vào `src/test/resources/application-test.properties`:

```properties
spring.jpa.properties.jakarta.persistence.schema-generation.scripts.action=create
spring.jpa.properties.jakarta.persistence.schema-generation.scripts.create-target=target/generated-ddl.sql
spring.jpa.properties.hibernate.hbm2ddl.delimiter=;
```

Chạy một test `@SpringBootTest` bất kỳ rồi lấy `target/generated-ddl.sql`. Đây là DDL của các bảng **đã có** (movie, showtime, room, seat, pricing_policy, user, ticket, booking cũ...).

## 2. Tạo `src/main/resources/db/migration/V1__baseline.sql`

Nội dung = DDL sinh ở §1 **cộng** 5 bảng mới **copy nguyên văn** từ `docs/BASELINE.md` §4:

`showtime_seat`, `booking`, `payment_transaction`, `refund_queue`, `booking_status_history`

Quy tắc khi trộn:

- Bảng `booking` cũ trong DDL sinh ra → **thay bằng** định nghĩa trong BASELINE §4 (có `hold_expires_at`, `active_key`).
- Bảng `ticket` giữ nguyên, chỉ đảm bảo có `UNIQUE (showtime_id, seat_id)`.
- **Không sáng tạo cột** ngoài BASELINE. Không đổi tên cột đã có.
- Cuối file: `INSERT` dữ liệu tối thiểu 1 cụm rạp, 1 phòng 120 ghế (A1–H15), 1 phim, 1 suất chiếu ngày mai.

## 3. Bật lại validate

`application.properties`: `spring.flyway.enabled=true`, `spring.jpa.hibernate.ddl-auto=validate`.

## Xong khi

```bash
mvn -q clean test
./scripts/verify.sh
```
Và chứng minh app khởi động được với MySQL trống: `mvn spring-boot:run` → log có `Successfully applied 1 migration`.
Dán output. Không sửa `V1__baseline.sql` sau commit này (đổi schema thì tạo `V2__`).
