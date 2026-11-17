package com.movie.ticket_booking.service.email;

import com.movie.ticket_booking.entity.Booking;
import com.movie.ticket_booking.entity.Ticket;
import com.movie.ticket_booking.entity.User;

import java.time.Instant;
import java.util.List;

public interface EmailService {

    void sendBookingConfirmation(Booking booking, List<Ticket> tickets);

    /** D-22: tell the customer the showtime was cancelled and the booking will be refunded. */
    void sendShowtimeCancelled(Booking booking, String reason);

    void sendWelcomeEmail(User user);

    void sendLoginNotification(User user, Instant at, String ipAddress, String userAgent);
}
