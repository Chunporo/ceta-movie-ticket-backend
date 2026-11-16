package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.Ticket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, Long> {

    boolean existsByShowtimeIdAndSeatId(Long showtimeId, Long seatId);

    List<Ticket> findByBookingIdOrderByIdAsc(Long bookingId);

    @Query("select t.seat.id from Ticket t where t.showtime.id = :showtimeId")
    List<Long> findSeatIdsByShowtimeId(@Param("showtimeId") Long showtimeId);

    @EntityGraph(attributePaths = {"booking", "seat", "showtime", "showtime.movie", "showtime.room"})
    Page<Ticket> findByBookingUserId(Long userId, Pageable pageable);

    @EntityGraph(attributePaths = {"booking", "booking.user", "seat", "showtime", "showtime.movie", "showtime.room"})
    @Query("select t from Ticket t where t.id = :id")
    Optional<Ticket> findDetailedById(@Param("id") Long id);
}
