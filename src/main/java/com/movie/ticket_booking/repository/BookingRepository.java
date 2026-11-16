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

    List<Booking> findByUserEmailOrderByCreatedAtDesc(String email);

    boolean existsByUserIdAndShowtimeIdAndStatus(Long userId, Long showtimeId, BookingStatus status);
}
