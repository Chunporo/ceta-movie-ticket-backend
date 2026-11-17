package com.movie.ticket_booking.service.ticket;

import com.movie.ticket_booking.dto.booking.CheckInRequest;
import com.movie.ticket_booking.dto.booking.CheckInResponse;
import com.movie.ticket_booking.entity.Showtime;
import com.movie.ticket_booking.entity.Ticket;
import com.movie.ticket_booking.entity.User;
import com.movie.ticket_booking.entity.enums.BookingStatus;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.TicketRepository;
import com.movie.ticket_booking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class CheckInServiceImpl implements CheckInService {
    private final UserRepository users;
    private final TicketRepository tickets;
    private final TicketQrService qr;
    private final com.movie.ticket_booking.repository.BookingRepository bookings;

    @Value("${app.ticket.checkin-opens-before:60m}")
    private Duration opensBefore;

    @Override
    @Transactional
    public CheckInResponse checkIn(String staffEmail, CheckInRequest request) {
        boolean hasQr = request != null && request.qr() != null && !request.qr().isBlank();
        boolean hasCode = request != null && request.code() != null && !request.code().isBlank();
        if (hasQr == hasCode) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Send exactly one of qr or code");
        }
        User staff = findUser(staffEmail);
        Ticket ticket = hasQr ? ticketFromQr(request.qr()) : ticketFromCode(request.code().trim());

        // Lock the booking row before judging it, like cancel/refund do: a customer cancelling at the same moment
        // either commits first (we see REFUNDED and refuse) or waits for us (their cancel then sees a used ticket).
        String bookingStatus = bookings.lockStatusById(ticket.getBooking().getId()).orElse(null);
        if (!BookingStatus.PAID.name().equals(bookingStatus)) {
            throw new BusinessException(HttpStatus.CONFLICT, "TICKET_NOT_VALID",
                    "The booking is " + bookingStatus + ", the ticket is not valid");
        }
        Showtime showtime = ticket.getShowtime();
        Instant now = Instant.now();
        if (now.isBefore(showtime.getStartTime().minus(opensBefore))) {
            throw new BusinessException(HttpStatus.CONFLICT, "CHECKIN_NOT_OPEN", "Check-in has not opened for this showtime");
        }
        if (now.isAfter(showtime.getStartTime().plus(TicketQueryServiceImpl.QR_GRACE))) {
            throw new BusinessException(HttpStatus.CONFLICT, "CHECKIN_CLOSED", "Check-in for this showtime has closed");
        }

        if (tickets.markCheckedIn(ticket.getId(), staff.getId(), now) == 0) {
            Instant first = tickets.findCheckedInAt(ticket.getId()).orElse(null);
            throw new BusinessException(HttpStatus.CONFLICT, "ALREADY_CHECKED_IN", "This ticket was already used",
                    List.of(Map.of("checkedInAt", String.valueOf(first))));
        }
        log.info("Ticket checked in ticketId={} staffId={}", ticket.getId(), staff.getId());
        return new CheckInResponse(ticket.getId(), ticket.getCode(), ticket.getSeat().getLabel(),
                showtime.getMovie().getTitle(), showtime.getRoom().getName(), showtime.getStartTime(), now);
    }

    private Ticket ticketFromQr(String payload) {
        Long ticketId;
        try {
            ticketId = qr.verify(payload);
        } catch (TicketQrService.InvalidQrException e) {
            boolean expired = "expired".equals(e.getMessage());
            throw new BusinessException(HttpStatus.BAD_REQUEST, expired ? "QR_EXPIRED" : "INVALID_QR",
                    expired ? "The QR code has expired" : "The QR code is not valid");
        }
        return tickets.findDetailedById(ticketId).orElseThrow(CheckInServiceImpl::ticketNotFound);
    }

    private Ticket ticketFromCode(String code) {
        return tickets.findDetailedByCode(code).orElseThrow(CheckInServiceImpl::ticketNotFound);
    }

    private User findUser(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return users.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private static BusinessException ticketNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "Ticket not found");
    }
}
