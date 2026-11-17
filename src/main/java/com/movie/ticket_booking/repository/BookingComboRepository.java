package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.BookingCombo;
import com.movie.ticket_booking.entity.BookingComboId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookingComboRepository extends JpaRepository<BookingCombo, BookingComboId> {
    List<BookingCombo> findByIdBookingId(Long bookingId);

    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = true)
    @org.springframework.data.jpa.repository.Query("delete from BookingCombo c where c.id.bookingId = :bookingId")
    int deleteAllForBooking(@org.springframework.data.repository.query.Param("bookingId") Long bookingId);
}
