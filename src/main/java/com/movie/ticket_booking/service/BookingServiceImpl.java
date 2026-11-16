package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.*;
import com.movie.ticket_booking.event.BookingCheckedOutEvent;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.BookingStatus;
import com.movie.ticket_booking.repository.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import com.movie.ticket_booking.dto.booking.SeatStatusUpdate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
@Slf4j
@Transactional
public class BookingServiceImpl implements BookingService {

    @org.springframework.beans.factory.annotation.Value("${app.booking.hold-ttl:10m}")
    private java.time.Duration holdTtl;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final ShowtimeRepository showtimeRepository;
    private final SeatRepository seatRepository;
    private final TicketRepository ticketRepository;
    private final BookingRepository bookingRepository;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final PricingService pricingService;
    private final VNPayService vnPayService;
    private final org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    public BookingServiceImpl(
            UserRepository userRepository,
            ShowtimeRepository showtimeRepository,
            SeatRepository seatRepository,
            TicketRepository ticketRepository,
            BookingRepository bookingRepository,
            ApplicationEventPublisher applicationEventPublisher,
            PricingService pricingService,
            VNPayService vnPayService,
            org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate
    ) {
        this.userRepository = userRepository;
        this.showtimeRepository = showtimeRepository;
        this.seatRepository = seatRepository;
        this.ticketRepository = ticketRepository;
        this.bookingRepository = bookingRepository;
        this.applicationEventPublisher = applicationEventPublisher;
        this.pricingService = pricingService;
        this.vnPayService = vnPayService;
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
        
        Set<Long> soldSeatIds  = new HashSet<>(ticketRepository.findSeatIdsByShowtimeId(showtimeId));

        return seats.stream()
                .map(seat -> {
                    BigDecimal seatPrice = pricingService.calculatePrice(showtime, seat);

                    if (!seat.isActive()) {
                        return toStatusResponse(seat, ShowtimeSeatStatus.INACTIVE, null, seatPrice);
                    }
                    if (soldSeatIds.contains(seat.getId())) {
                        return toStatusResponse(seat, ShowtimeSeatStatus.SOLD, null, seatPrice);
                    }
                    return toStatusResponse(seat, ShowtimeSeatStatus.AVAILABLE, null, seatPrice);
                })
                .toList();
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

        for (Long seatId : seatIds) {
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

        // Create booking as PENDING (will be marked PAID after VNPay callback)
        Booking booking = Booking.builder()
                .user(user)
                .showtime(showtime)
                .status(BookingStatus.PENDING)
                .totalAmount(total.longValueExact())
                .holdExpiresAt(now.plus(holdTtl))
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
                        .price(pricingService.calculatePrice(showtime, seat))
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

        // Broadcast to other clients
        messagingTemplate.convertAndSend("/topic/seats/" + showtime.getId(),
            SeatStatusUpdate.builder()
                .showtimeId(showtime.getId())
                .seatIds(new ArrayList<>(seatIds))
                .status("BOOKED")
                .build()
        );

        // Build VNPay payment URL
        HttpServletRequest httpRequest = null;
        try {
            httpRequest = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        } catch (Exception ignored) {}
        String ipAddr = httpRequest != null ? VNPayService.getClientIp(httpRequest) : "127.0.0.1";
        String orderInfo = "Dat ve phim: " + showtime.getMovie().getTitle() + " #" + savedBooking.getId();
        String paymentUrl = vnPayService.buildPaymentUrl(savedBooking.getId(), total, orderInfo, ipAddr);

        return BookingCheckoutResponse.builder()
                .bookingId(savedBooking.getId())
                .status(savedBooking.getStatus())
                .totalAmount(savedBooking.getTotalAmount())
                .paidAt(savedBooking.getPaidAt())
                .tickets(ticketItems)
                .paymentUrl(paymentUrl)

                .build();
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

    private String generateTicketCode() {
        byte[] bytes = new byte[9];
        SECURE_RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        return "TCK-" + raw;
    }

}
