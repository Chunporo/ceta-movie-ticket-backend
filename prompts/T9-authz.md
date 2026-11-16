# T9 — Siết phân quyền từng endpoint

Đọc `AGENTS.md` §5.

## Việc

1. Bỏ hoàn toàn fallback `.anyRequest().hasRole("ADMIN")` trong `SecurityConfig`.
2. Thêm `@PreAuthorize` cho **mọi** endpoint:

| Nhóm | Quyền |
|---|---|
| `GET /movies`, `GET /showtimes`, `GET /showtimes/{id}/seats` | `permitAll` |
| `POST /bookings`, `DELETE`, `GET /bookings/{id}`, `GET /tickets/my` | `isAuthenticated()` + kiểm ownership trong service |
| `POST /payments/vnpay/create-url` | `isAuthenticated()` |
| `POST /payments/vnpay/ipn`, `GET .../return` | `permitAll` (chữ ký là auth) |
| `/api/v1/admin/**` | `hasRole('ADMIN')` |

3. `@EnableMethodSecurity`.
4. Viết test 403/401 cho từng nhóm, gồm cả ca **user A đọc booking của user B** → 403.

## Xong khi

`grep -rn 'anyRequest' src/main` không còn `hasRole("ADMIN")`. Mỗi controller method đều có `@PreAuthorize`.
`./scripts/verify.sh` PASS. Dán output.
