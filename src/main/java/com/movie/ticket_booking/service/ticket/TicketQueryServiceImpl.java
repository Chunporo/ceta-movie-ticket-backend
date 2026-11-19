package com.movie.ticket_booking.service.ticket;

import com.movie.ticket_booking.dto.booking.PageResponse;
import com.movie.ticket_booking.dto.booking.TicketResponse;
import com.movie.ticket_booking.entity.Showtime;
import com.movie.ticket_booking.entity.Ticket;
import com.movie.ticket_booking.entity.User;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.TicketRepository;
import com.movie.ticket_booking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TicketQueryServiceImpl implements TicketQueryService {
    static final int MAX_PAGE_SIZE = 50;
    /** The QR stops being valid two hours after the film starts (architecture 9). */
    static final Duration QR_GRACE = Duration.ofHours(2);

    private final UserRepository users;
    private final TicketRepository tickets;
    private final TicketQrService qr;

    @Override
    public PageResponse<TicketResponse> myTickets(String requesterEmail, int page, int size) {
        User user = findUser(requesterEmail);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Order.desc("showtime.startTime"), Sort.Order.asc("id")));
        Page<Ticket> result = tickets.findByBookingUserId(user.getId(), pageable);
        return new PageResponse<>(result.map(this::toResponse).getContent(), result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages());
    }

    @Override
    public TicketResponse get(String requesterEmail, Long ticketId) {
        User user = findUser(requesterEmail);
        Ticket ticket = tickets.findDetailedById(ticketId)
                .filter(t -> t.getBooking().getUser().getId().equals(user.getId()))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "Ticket not found"));
        return toResponse(ticket);
    }

    private TicketResponse toResponse(Ticket t) {
        Showtime showtime = t.getShowtime();
        com.movie.ticket_booking.entity.Cinema cinema = showtime.getRoom().getCinema();
        return new TicketResponse(t.getId(), t.getBooking().getId(), t.getCode(), t.getSeat().getId(), t.getSeat().getLabel(),
                t.getPrice().longValue(),
                new TicketResponse.Showtime(showtime.getId(), showtime.getStartTime(), showtime.getEndTime(),
                        showtime.getMovie().getTitle(), showtime.getRoom().getName(),
                        cinema == null ? null : cinema.getName(), cinema == null ? null : cinema.getAddress(),
                        showtime.getFormat(), showtime.getMovie().getPosterUrl(),
                        showtime.getMovie().getAgeRating() == null ? null : showtime.getMovie().getAgeRating().name()),
                qr.sign(t.getId(), showtime.getStartTime().plus(QR_GRACE)), t.getCheckedInAt() != null);
    }

    private User findUser(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return users.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }
}
