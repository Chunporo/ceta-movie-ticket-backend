# AGENTS.md — CETA MovieTicket Backend

Đọc file này TRƯỚC khi sửa bất kỳ dòng code nào. Đây là hợp đồng, không phải gợi ý.

## 1. Đây là gì

Backend đặt vé xem phim cho **một cụm rạp** (monolith, không microservices).
Nguồn sự thật về nghiệp vụ: `docs/BASELINE.md`. **Nơi nào tài liệu khác mâu thuẫn, `docs/BASELINE.md` thắng.**

Stack: Spring Boot 3.4.1 · Java 21 · MySQL 8 · Redis **chỉ để cache đọc** · JWT HS256 · VNPay · Flyway.

## 2. Bất biến — vi phạm là bug P0

1. Không có hai `ticket` sống cho cùng `(showtime_id, seat_id)`.
2. `showtime_seat.status = 'HELD'` ⟺ `held_until IS NOT NULL AND booking_id IS NOT NULL`.
3. `HELD` luôn thuộc đúng **một** booking, và booking đó phải `PENDING`.
4. `Σ ticket.price + combo_amount − discount_amount − points_amount == booking.totalAmount` — assert trước khi commit ở payment callback (giảm giá và điểm thêm ở B4/B5, trước đó bằng 0).
5. Một `txn_ref` chỉ tạo **một** vé (`payment_transaction.processed_at` là cờ idempotency).
6. Chỉ module `inventory` được UPDATE bảng `showtime_seat`. Enforce bằng ArchUnit.
7. `booking.totalAmount` chốt lúc tạo đơn. **Không** gọi lại `pricingService.calculate()` ở bước claim.
8. Mỗi user tối đa **1 booking PENDING** / suất chiếu (unique index trên cột sinh `active_key`).

## 3. Bị cấm — grep được, đừng viết

| Cấm | Vì sao |
|---|---|
| `redisTemplate.keys(...)` | Quét toàn bộ keyspace, chặn Redis, không scale |
| `hasKey()` rồi `set()` để giữ ghế | Không atomic — đây là bug gốc của code cũ |
| Bất kỳ `inMemoryFallback` / `ConcurrentHashMap` làm nguồn sự thật | Không sống sót restart, không chia sẻ giữa instance |
| `spring.jpa.hibernate.ddl-auto` khác `validate` | Đổi schema bằng Hibernate = mất dữ liệu khách |
| Logic nghiệp vụ trong `@Controller` | Controller chỉ map DTO → service |
| `@Transactional` trên `private` method | Không có tác dụng — Spring AOP |
| Thêm dependency mới | Phải hỏi trước |
| Format lại / rename file không liên quan tới task | Làm diff không review được |
| `System.out.println` | Dùng `@Slf4j` |
| Trả `Entity` ra API | Luôn map sang DTO |

## 4. Cấu trúc package (không tự đổi)

```
com.movie.ticket_booking
├── config/        SecurityConfig, RedisConfig, VnPayConfig, WebConfig
├── controller/    mỏng, chỉ DTO
├── service/       interface + impl
│   └── inventory/  ← MỌI thao tác trên showtime_seat nằm ở đây
├── repository/    Spring Data JPA
├── entity/        JPA entity
├── dto/           request/response
├── exception/     GlobalExceptionHandler + BusinessException(code)
└── security/      JwtFilter, UserPrincipal
```

## 5. Quy ước API

- Base path `/api/v1`. Không version trong body.
- Lỗi: `{"code":"SEAT_CONFLICT","message":"...","details":[{"seatId":7}]}` — `code` là hằng chuỗi, app map sang tiếng Việt. **Không** đổi `code` đã có trong `docs/BASELINE.md` §7.
- Tiền: `Long` VND, đơn vị đồng, không phần lẻ, không `double`.
- Thời gian: `Instant` trong code, lưu UTC, trả ISO-8601.
- Endpoint mới phải có `@PreAuthorize`, không dựa vào fallback `hasRole('ADMIN')`.

## 6. Build & test

```bash
mvn -q clean compile          # phải xanh trước khi bắt đầu bất kỳ task nào
mvn -q test                   # unit test, KHÔNG cần MySQL
mvn -q -Pit-mysql test        # test đồng thời, CẦN MySQL local đang chạy
```

- Test không được gọi mạng thật (VNPay) — mock `VnPayClient`.
- **Trước khi báo xong, bắt buộc chạy `./scripts/verify.sh` và dán output.** Script chặn pattern bị cấm và sửa file cấm.
- Test DB: H2 in-memory cho unit; **MySQL thật** cho test khoá dòng (H2 không mô phỏng đúng `FOR UPDATE`).
- Không được `@Disabled` test đang đỏ. Sửa code hoặc xoá test đó kèm lý do trong commit.

## 7. Bảng task

Task hiện tại và thứ tự: `TASKS.md`. Mỗi phiên làm đúng một task, không gộp.

## 8. Cách làm việc

1. **Một task = một commit.** Không gộp.
2. Trước khi code: đọc `docs/BASELINE.md` phần liên quan + file đang có.
3. Viết test trước cho hành vi mới, rồi implement.
4. Xong: chạy `mvn -q test` và **dán output thật** vào câu trả lời. Không nói "đã pass" mà không có output.
5. Nếu task cần đổi schema → sửa migration mới (`V2__...`), **không sửa** `V1__baseline.sql`.
6. Nếu phát hiện baseline sai/thiếu → **dừng, báo**, không tự quyết.
7. Không bao giờ commit: `application-local.properties`, `*.jks`, `build.log`, `target/`.

## 9. File không được sửa

`docs/BASELINE.md` · `AGENTS.md` · `src/main/resources/db/migration/V1__baseline.sql` · `pom.xml` (trừ khi task cho phép rõ ràng).
