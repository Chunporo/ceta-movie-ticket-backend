package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.BookingSeat;
import com.movie.ticket_booking.entity.BookingSeatId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BookingSeatRepository extends JpaRepository<BookingSeat, BookingSeatId> {
}
