package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.*;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.SeatInventoryStatus;
import com.movie.ticket_booking.service.inventory.InventoryService;
import com.movie.ticket_booking.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
@Slf4j
@Transactional
public class BookingServiceImpl implements BookingService {

    private final UserRepository userRepository;
    private final ShowtimeRepository showtimeRepository;
    private final SeatRepository seatRepository;
    private final BookingRepository bookingRepository;
    private final PricingService pricingService;
    private final InventoryService inventory;

    public BookingServiceImpl(
            UserRepository userRepository,
            ShowtimeRepository showtimeRepository,
            SeatRepository seatRepository,
            BookingRepository bookingRepository,
            PricingService pricingService,
            InventoryService inventory
    ) {
        this.userRepository = userRepository;
        this.showtimeRepository = showtimeRepository;
        this.seatRepository = seatRepository;
        this.bookingRepository = bookingRepository;
        this.pricingService = pricingService;
        this.inventory = inventory;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShowtimeSeatStatusResponse> getShowtimeSeats(Long showtimeId, String requesterEmail) {
        Showtime showtime = showtimeRepository.findById(showtimeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Showtime not found"));

        Long roomId = showtime.getRoom().getId();
        List<Seat> seats = seatRepository.findByRoomIdOrderByRowNumberAscColumnNumberAsc(roomId);

        // Force-load room (LAZY) before streaming to avoid LazyInitializationException in PricingService
        Room room = showtime.getRoom();
        room.getId(); // triggers initialization
        
        // Inventory is the source of truth (HELD and SOLD); only the status is exposed, never hold times.
        Map<Long, SeatInventoryStatus> inventoryStatus = inventory.seatStatuses(showtimeId);

        return seats.stream()
                .map(seat -> {
                    BigDecimal seatPrice = pricingService.calculatePrice(showtime, seat);

                    if (!seat.isActive()) {
                        return toStatusResponse(seat, ShowtimeSeatStatus.INACTIVE, null, seatPrice);
                    }
                    SeatInventoryStatus state = inventoryStatus.get(seat.getId());
                    if (state == null || state == SeatInventoryStatus.BLOCKED) {
                        return toStatusResponse(seat, ShowtimeSeatStatus.INACTIVE, null, seatPrice);
                    }
                    ShowtimeSeatStatus shown = switch (state) {
                        case AVAILABLE -> ShowtimeSeatStatus.AVAILABLE;
                        case HELD -> ShowtimeSeatStatus.HELD;
                        case SOLD -> ShowtimeSeatStatus.SOLD;
                        case BLOCKED -> ShowtimeSeatStatus.INACTIVE;
                    };
                    return toStatusResponse(seat, shown, null, seatPrice);
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<BookingHistoryResponse> getUserBookings(String requesterEmail) {
        User user = findUserByEmailOrThrow(requesterEmail);
        List<Booking> bookings = bookingRepository.findByUserEmailOrderByCreatedAtDesc(user.getEmail());

        return bookings.stream().map(booking -> {
            Showtime showtime = booking.getShowtime();
            Movie movie = showtime.getMovie();
            Room room = showtime.getRoom();

            List<String> seatNames = booking.getTickets().stream()
                    .map(ticket -> ticket.getSeat().getLabel())
                    .toList();

            return BookingHistoryResponse.builder()
                    .id(booking.getId())
                    .status(booking.getStatus())
                    .totalAmount(booking.getTotalAmount())
                    .createdAt(booking.getCreatedAt())
                    .showtimeId(showtime.getId())
                    .showDate(showtime.getStartTime()) // use startTime as showDate
                    .startTime(showtime.getStartTime())
                    .endTime(showtime.getEndTime())
                    .movieId(movie.getId())
                    .movieTitle(movie.getTitle())
                    .moviePosterUrl(movie.getPosterUrl())
                    .roomName(room.getName())
                    .seatNames(seatNames)
                    .build();
        }).toList();
    }

    private ShowtimeSeatStatusResponse toStatusResponse(Seat seat, ShowtimeSeatStatus status, Instant holdExpiresAt, java.math.BigDecimal price) {
        return ShowtimeSeatStatusResponse.builder()
                .seatId(seat.getId())
                .label(seat.getLabel())
                .rowNumber(seat.getRowNumber())
                .columnNumber(seat.getColumnNumber())
                .type(seat.getType())
                .active(seat.isActive())
                .status(status)
                .price(price)
                .holdExpiresAt(holdExpiresAt)
                .build();
    }


    private User findUserByEmailOrThrow(String requesterEmail) {
        if (requesterEmail == null || requesterEmail.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        String email = requesterEmail.trim().toLowerCase();
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

}
