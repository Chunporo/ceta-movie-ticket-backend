package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.BookingSeat;
import com.movie.ticket_booking.entity.BookingSeatId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookingSeatRepository extends JpaRepository<BookingSeat, BookingSeatId> {
    List<BookingSeat> findByIdBookingId(Long bookingId);
}
