package com.movie.ticket_booking.service.email;

import com.movie.ticket_booking.entity.Booking;
import com.movie.ticket_booking.entity.Ticket;
import com.movie.ticket_booking.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "app.email.enabled", havingValue = "true")
@Slf4j
public class SmtpEmailService implements EmailService {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpEmailService(JavaMailSender mailSender, @Value("${app.email.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendBookingConfirmation(Booking booking, List<Ticket> tickets) {
        String to = booking.getUser().getEmail();
        if (!User.isDeliverable(to)) {
            log.warn("Skip booking email: missing contactEmail bookingId={}", booking.getId());
            return;
        }

        String seatLines = tickets.stream()
                .map(t -> "- Seat: " + safeSeatLabel(t) + " | Code: " + t.getCode() + " | Price: " + t.getPrice())
                .collect(Collectors.joining("\n"));

        String showtime = booking.getShowtime() == null ? "" : ("Showtime: " + TIME_FMT.format(booking.getShowtime().getStartTime()));
        String movie = booking.getShowtime() != null && booking.getShowtime().getMovie() != null ? booking.getShowtime().getMovie().getTitle() : null;
        String room = booking.getShowtime() != null && booking.getShowtime().getRoom() != null ? booking.getShowtime().getRoom().getName() : null;

        String body = "Booking confirmed!\n" +
                "Booking ID: " + booking.getId() + "\n" +
                (movie == null ? "" : ("Movie: " + movie + "\n")) +
                (room == null ? "" : ("Room: " + room + "\n")) +
                showtime + "\n" +
                "Total: " + booking.getTotalAmount() + "\n" +
                "\nTickets:\n" + seatLines + "\n";

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("[TicketBooking] Booking confirmed #" + booking.getId());
        message.setText(body);

        mailSender.send(message);
        log.info("Sent booking confirmation email bookingId={} to={}", booking.getId(), to);
    }

    @Override
    public void sendWelcomeEmail(User user) {
        if (!User.isDeliverable(user.getEmail())) {
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(user.getEmail());
        message.setSubject("[TicketBooking] Welcome");
        message.setText("Welcome to TicketBooking!\n\nEmail: " + user.getEmail());

        mailSender.send(message);
        log.info("Sent welcome email userId={} to={}", user.getId(), user.getEmail());
    }

    @Override
    public void sendShowtimeCancelled(Booking booking, String reason) {
        if (!User.isDeliverable(booking.getUser().getEmail())) {
            return;
        }
        var showtime = booking.getShowtime();
        String body = "Your showtime has been cancelled.\n"
                + "Movie: " + showtime.getMovie().getTitle() + "\n"
                + "Was scheduled: " + TIME_FMT.format(showtime.getStartTime()) + "\n"
                + "Reason: " + reason + "\n"
                + "Booking #" + booking.getId() + " (" + booking.getTotalAmount() + " VND) will be refunded in full; "
                + "we will contact you if we need anything else.\n";
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(booking.getUser().getEmail());
        message.setSubject("[TicketBooking] Showtime cancelled - booking #" + booking.getId());
        message.setText(body);
        mailSender.send(message);
        log.info("Sent showtime cancellation bookingId={} to={}", booking.getId(), booking.getUser().getEmail());
    }

    @Override
    public void sendPasswordReset(User user, String resetLink) {
        if (!User.isDeliverable(user.getEmail())) {
            return;
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(user.getEmail());
        message.setSubject("[TicketBooking] Reset your password");
        message.setText("Open this link to choose a new password (valid for a short time, usable once):\n" + resetLink
                + "\n\nIf you did not ask for this, ignore this email; your password has not changed.");
        mailSender.send(message);
        log.info("Sent password reset userId={}", user.getId());
    }

    @Override
    public void sendLoginNotification(User user, Instant at, String ipAddress, String userAgent) {
        if (!User.isDeliverable(user.getEmail())) {
            return;
        }

        String body = "New login detected.\n" +
                "Time: " + TIME_FMT.format(at) + "\n" +
                (ipAddress == null ? "" : ("IP: " + ipAddress + "\n")) +
                (userAgent == null ? "" : ("User-Agent: " + userAgent + "\n"));

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(user.getEmail());
        message.setSubject("[TicketBooking] Login notification");
        message.setText(body);

        try {
            mailSender.send(message);
            log.info("Sent login notification userId={} to={}", user.getId(), user.getEmail());
        } catch (Exception ex) {
            // A mail outage must never stop someone from signing in.
            log.warn("Could not send login notification userId={}: {}", user.getId(), ex.toString());
        }
    }

    private String safeSeatLabel(Ticket ticket) {
        try {
            if (ticket.getSeat() == null) {
                return "(unknown)";
            }
            return ticket.getSeat().getLabel();
        } catch (Exception ex) {
            return "(unknown)";
        }
    }
}
