package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.*;
import com.movie.ticket_booking.event.BookingCheckedOutEvent;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.BookingStatus;
import com.movie.ticket_booking.repository.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import com.movie.ticket_booking.dto.booking.SeatStatusUpdate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
@Slf4j
@Transactional
public class BookingServiceImpl implements BookingService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final ShowtimeRepository showtimeRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final BookingRepository bookingRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final SeatReservationService seatReservationService;
    private final PricingService pricingService;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    public BookingServiceImpl(
            UserRepository userRepository,
            ShowtimeRepository showtimeRepository,
            SeatRepository seatRepository,
            TicketRepository ticketRepository,
            BookingRepository bookingRepository,
            ApplicationEventPublisher applicationEventPublisher,
            SeatReservationService seatReservationService,
            PricingService pricingService,
            org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate
    ) {
        this.userRepository = userRepository;
        this.showtimeRepository = showtimeRepository;
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
        this.bookingRepository = bookingRepository;
        this.applicationEventPublisher = applicationEventPublisher;
        this.seatReservationService = seatReservationService;
        this.pricingService = pricingService;
        this.messagingTemplate = messagingTemplate;
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
        
        Set<Long> soldSeatIds = new HashSet<>(ticketRepository.findSeatIdsByShowtimeId(showtimeId));
        Set<Long> heldSeatIds = seatReservationService.getHeldSeatIds(showtimeId);

        return seats.stream()
                .map(seat -> {
                    BigDecimal seatPrice = pricingService.calculatePrice(showtime, seat);

                    if (!seat.isActive()) {
                        return toStatusResponse(seat, ShowtimeSeatStatus.INACTIVE, null, seatPrice);
                    }
                    if (soldSeatIds.contains(seat.getId())) {
                        return toStatusResponse(seat, ShowtimeSeatStatus.SOLD, null, seatPrice);
                    }
                    if (heldSeatIds.contains(seat.getId())) {
                        return toStatusResponse(seat, ShowtimeSeatStatus.HELD, null, seatPrice);
                    }

                    return toStatusResponse(seat, ShowtimeSeatStatus.AVAILABLE, null, seatPrice);
                })
                .toList();
    }

    @Override
    public List<HoldSeatResponse> holdSeats(String requesterEmail, HoldSeatsRequest request) {
        User user = findUserByEmailOrThrow(requesterEmail);
        Showtime showtime = showtimeRepository.findById(request.getShowtimeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Showtime not found"));

        Instant now = Instant.now();
        if (!now.isBefore(showtime.getStartTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot hold seats for a started/ended showtime");
        }

        Set<Long> seatIds = new LinkedHashSet<>(request.getSeatIds());
        if (seatIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "seatIds must not be empty");
        }

        Map<Long, Seat> seatsById = new HashMap<>();
        for (Seat seat : seatRepository.findAllById(seatIds)) {
            seatsById.put(seat.getId(), seat);
        }
        if (seatsById.size() != seatIds.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "One or more seats not found");
        }

        Long showtimeRoomId = showtime.getRoom().getId();
        for (Seat seat : seatsById.values()) {
            if (!seat.isActive()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat is inactive: " + seat.getLabel());
            }
            if (!Objects.equals(seat.getRoom().getId(), showtimeRoomId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seat does not belong to the showtime room: " + seat.getId());
            }
            if (ticketRepository.existsByShowtimeIdAndSeatId(showtime.getId(), seat.getId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat already sold: " + seat.getLabel());
            }
        }

        boolean success = seatReservationService.holdSeats(showtime.getId(), new ArrayList<>(seatIds), user.getEmail());
        if (!success) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "One or more seats are already held or booked");
        }

        // Broadcast to other clients
        messagingTemplate.convertAndSend("/topic/seats/" + showtime.getId(), 
            SeatStatusUpdate.builder()
                .showtimeId(showtime.getId())
                .seatIds(new ArrayList<>(seatIds))
                .status("HELD")
                .build()
        );

        return seatIds.stream()
                .map(id -> HoldSeatResponse.builder()
                        .seatId(id)
                        .expiresAt(Instant.now().plusSeconds(600)) // 10 mins
                        .build())
                .toList();
    }

    @Override
    public void releaseSeats(String requesterEmail, ReleaseSeatsRequest request) {
        User user = findUserByEmailOrThrow(requesterEmail);

        Showtime showtime = showtimeRepository.findById(request.getShowtimeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Showtime not found"));

        Instant now = Instant.now();
        Set<Long> seatIds = new LinkedHashSet<>(request.getSeatIds());

        seatReservationService.releaseSeats(showtime.getId(), new ArrayList<>(seatIds));

        // Broadcast to other clients
        messagingTemplate.convertAndSend("/topic/seats/" + showtime.getId(), 
            SeatStatusUpdate.builder()
                .showtimeId(showtime.getId())
                .seatIds(new ArrayList<>(seatIds))
                .status("AVAILABLE")
                .build()
        );
    }

    @Override
    public BookingCheckoutResponse checkout(String requesterEmail, CheckoutRequest request) {
        User user = findUserByEmailOrThrow(requesterEmail);
        Showtime showtime = showtimeRepository.findById(request.getShowtimeId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Showtime not found"));

        Instant now = Instant.now();
        if (!now.isBefore(showtime.getStartTime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot checkout for a started/ended showtime");
        }

        Set<Long> seatIds = new LinkedHashSet<>(request.getSeatIds());
        if (seatIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "seatIds must not be empty");
        }

        Map<Long, Seat> seatsById = new HashMap<>();
        for (Seat seat : seatRepository.findAllById(seatIds)) {
            seatsById.put(seat.getId(), seat);
        }
        if (seatsById.size() != seatIds.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "One or more seats not found");
        }

        Long showtimeRoomId = showtime.getRoom().getId();
        for (Seat seat : seatsById.values()) {
            if (!Objects.equals(seat.getRoom().getId(), showtimeRoomId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Seat does not belong to the showtime room: " + seat.getId());
            }
        }

        // Verify Redis holds
        Set<Long> heldSeatIds = seatReservationService.getHeldSeatIds(showtime.getId());
        for (Long seatId : seatIds) {
            if (!heldSeatIds.contains(seatId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat hold expired or not found: " + seatId);
            }
            if (ticketRepository.existsByShowtimeIdAndSeatId(showtime.getId(), seatId)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Seat already sold: " + seatId);
            }
        }

        // Dynamic Pricing Calculation
        BigDecimal total = BigDecimal.ZERO;
        for (Long seatId : seatIds) {
            Seat seat = seatsById.get(seatId);
            total = total.add(pricingService.calculatePrice(showtime, seat));
        }

        Booking booking = Booking.builder()
                .user(user)
                .showtime(showtime)
                .status(BookingStatus.PAID)
                .totalAmount(total)
                .paymentProvider(request.getPaymentProvider())
                .paymentTransactionId(generatePaymentTransactionId())
                .paidAt(now)
                .contactEmail(request.getContactEmail())
                .build();

        Booking savedBooking = bookingRepository.saveAndFlush(booking);

        List<TicketItemResponse> ticketItems = new ArrayList<>();
        try {
            for (Long seatId : seatIds) {
                Seat seat = seatsById.get(seatId);
                Ticket ticket = Ticket.builder()
                        .booking(savedBooking)
                        .showtime(showtime)
                        .seat(seat)
                        .price(showtime.getBasePrice())
                        .code(generateTicketCode())
                        .build();

                Ticket savedTicket = ticketRepository.save(ticket);
                ticketItems.add(TicketItemResponse.builder()
                        .ticketId(savedTicket.getId())
                        .seatId(seatId)
                        .seatLabel(seat.getLabel())
                        .price(savedTicket.getPrice())
                        .code(savedTicket.getCode())
                        .build());
            }
            ticketRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            log.warn("Checkout conflict: showtimeId={} seatIds={} userId={}", showtime.getId(), seatIds, user.getId());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "One or more seats were already booked", ex);
        }

        applicationEventPublisher.publishEvent(new BookingCheckedOutEvent(savedBooking.getId()));

        seatReservationService.releaseSeats(showtime.getId(), new ArrayList<>(seatIds));

        // Broadcast booked status
        messagingTemplate.convertAndSend("/topic/seats/" + showtime.getId(), 
            SeatStatusUpdate.builder()
                .showtimeId(showtime.getId())
                .seatIds(new ArrayList<>(seatIds))
                .status("BOOKED")
                .build()
        );

        return BookingCheckoutResponse.builder()
                .bookingId(savedBooking.getId())
                .status(savedBooking.getStatus())
                .totalAmount(savedBooking.getTotalAmount())
                .paidAt(savedBooking.getPaidAt())
                .tickets(ticketItems)
                .build();
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

    private String generateTicketCode() {
        byte[] bytes = new byte[9];
        SECURE_RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return "TCK-" + raw;
    }

    private String generatePaymentTransactionId() {
        byte[] bytes = new byte[12];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
