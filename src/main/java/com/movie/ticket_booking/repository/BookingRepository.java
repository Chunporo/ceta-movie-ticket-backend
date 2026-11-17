package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.Booking;
import com.movie.ticket_booking.entity.enums.BookingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {
    @Override
    @EntityGraph(attributePaths = "user")
    Optional<Booking> findById(Long id);

    /** Serialises cancel against the sweeper and payment callbacks on the same booking. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.id = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") Long id);

    /** Locks the row and returns its current status (a locking read sees the latest commit, unlike a snapshot read). */
    @Query(value = "SELECT status FROM booking WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<String> lockStatusById(@Param("id") Long id);

    /** Locks overdue PENDING rows in id order, the same order payment callbacks lock them. */
    @Query(value = """
            SELECT id FROM booking
            WHERE status = 'PENDING' AND hold_expires_at < :now
            ORDER BY id LIMIT :limit FOR UPDATE
            """, nativeQuery = true)
    List<Long> lockOverduePendingIds(@Param("now") java.time.Instant now, @Param("limit") int limit);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE booking SET status = 'EXPIRED' WHERE id IN (:ids) AND status = 'PENDING'", nativeQuery = true)
    int expireByIds(@Param("ids") List<Long> ids);

    List<Booking> findByUserEmailOrderByCreatedAtDesc(String email);

    /** Ids of the showtime's bookings that cancellation still has to deal with, oldest first. */
    @Query("select b.id from Booking b where b.showtime.id = :showtimeId and b.status in :statuses order by b.id")
    List<Long> findIdsByShowtimeIdAndStatusIn(@Param("showtimeId") Long showtimeId,
                                              @Param("statuses") java.util.Collection<BookingStatus> statuses);

    boolean existsByUserIdAndStatusAndShowtimeEndTimeAfter(Long userId, BookingStatus status, java.time.Instant after);

    @Query("select b.id from Booking b where b.user.id = :userId and b.status = :status order by b.id")
    List<Long> findIdsByUserIdAndStatus(@Param("userId") Long userId, @Param("status") BookingStatus status);

    org.springframework.data.domain.Page<Booking> findByUserEmailOrderByCreatedAtDesc(String email, org.springframework.data.domain.Pageable pageable);

    boolean existsByShowtimeId(Long showtimeId);

    long countByUserIdAndShowtimeIdAndCreatedAtAfter(Long userId, Long showtimeId, java.time.Instant since);

    boolean existsByUserIdAndShowtimeIdAndStatus(Long userId, Long showtimeId, BookingStatus status);

    boolean existsByUserIdAndStatus(Long userId, BookingStatus status);
}
