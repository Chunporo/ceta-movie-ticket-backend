# T2 — `InventoryService` + ArchUnit

Đọc `AGENTS.md` §2, §4 và `docs/BASELINE.md` §6.

## Tạo `service/inventory/`

```java
public interface InventoryService {
    List<Long> claim(Long showtimeId, List<Long> seatIds, Long bookingId, Instant expiresAt); // rỗng = OK
    void release(Long bookingId);
    void promoteToSold(Long bookingId);
    int releaseExpired();
}
```

`InventoryServiceImpl.claim()` — **đúng thứ tự này, trong một `@Transactional`**:

1. `SELECT id FROM showtime WHERE id = :id FOR UPDATE` (khoá dòng suất chiếu).
2. Đếm ghế: `SELECT COUNT(*) FROM showtime_seat WHERE showtime_id=:s AND seat_id IN (:ids) AND status='AVAILABLE'`.
3. Nếu `count != ids.size()` → trả về danh sách ghế không AVAILABLE (`SeatConflictException`), **không UPDATE gì**.
4. `UPDATE showtime_seat SET status='HELD', held_until=:exp, booking_id=:b WHERE showtime_id=:s AND seat_id IN (:ids) AND status='AVAILABLE'`.
5. Assert `affectedRows == ids.size()`, nếu không → ném exception để rollback.

`release(bookingId)`: `UPDATE ... SET status='AVAILABLE', held_until=NULL, booking_id=NULL WHERE booking_id=:b AND status='HELD'` — idempotent.
`promoteToSold(bookingId)`: `... WHERE booking_id=:b AND status='HELD'` → trả `affectedRows`.
`releaseExpired()`: `... WHERE status='HELD' AND held_until < NOW()`.

## Exception

`SeatConflictException` trong `exception/`, mang `List<Long> conflictSeatIds`. Map trong `GlobalExceptionHandler` → **409**, body `{"code":"SEAT_CONFLICT","message":"...","details":[{"seatId":7}]}`.

## ArchUnit

`src/test/java/.../architecture/InventoryBoundaryTest.java`:
không class nào ngoài `service.inventory..` được gọi `ShowtimeSeatRepository` (kiểm bằng `noClasses().that().resideOutsideOfPackage("..service.inventory..").should().dependOnClassesThat().haveSimpleName("ShowtimeSeatRepository")`).

## Xong khi

`mvn -q clean test` xanh với 3 unit test: claim đủ ghế / claim thiếu ghế / release 2 lần không lỗi.
`./scripts/verify.sh` PASS. Dán output.
