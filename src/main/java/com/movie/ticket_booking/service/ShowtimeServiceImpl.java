package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.MovieSummaryResponse;
import com.movie.ticket_booking.dto.RoomSummaryResponse;
import com.movie.ticket_booking.dto.ShowtimeRequest;
import com.movie.ticket_booking.dto.ShowtimeResponse;
import com.movie.ticket_booking.entity.Movie;
import com.movie.ticket_booking.entity.Room;
import com.movie.ticket_booking.entity.Showtime;
import com.movie.ticket_booking.repository.MovieRepository;
import com.movie.ticket_booking.repository.RoomRepository;
import com.movie.ticket_booking.repository.ShowtimeRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.BookingRepository;
import com.movie.ticket_booking.service.inventory.InventoryService;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class ShowtimeServiceImpl implements ShowtimeService {

    private final ShowtimeRepository showtimeRepository;
    private final MovieRepository movieRepository;
    private final RoomRepository roomRepository;
    private final BookingRepository bookingRepository;
    private final InventoryService inventory;

    public ShowtimeServiceImpl(ShowtimeRepository showtimeRepository, MovieRepository movieRepository, RoomRepository roomRepository,
                               BookingRepository bookingRepository, InventoryService inventory) {
        this.showtimeRepository = showtimeRepository;
        this.movieRepository = movieRepository;
        this.roomRepository = roomRepository;
        this.bookingRepository = bookingRepository;
        this.inventory = inventory;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ShowtimeResponse> getAll(UUID movieId, Long roomId, Long cinemaId, com.movie.ticket_booking.entity.enums.MovieFormat format, Instant from, Instant to) {
        return showtimeRepository.findAll().stream()
                .filter(s -> movieId == null || (s.getMovie() != null && movieId.equals(s.getMovie().getId())))
                .filter(s -> roomId == null || (s.getRoom() != null && roomId.equals(s.getRoom().getId())))
                .filter(s -> cinemaId == null || (s.getRoom() != null && s.getRoom().getCinema() != null
                        && cinemaId.equals(s.getRoom().getCinema().getId())))
                .filter(s -> format == null || s.getFormat() == format)
                .filter(s -> from == null || !s.getStartTime().isBefore(from))
                .filter(s -> to == null || !s.getStartTime().isAfter(to))
                .sorted(Comparator.comparing(Showtime::getStartTime))
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ShowtimeResponse getById(Long id) {
        Showtime showtime = showtimeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Showtime not found"));
        return toResponse(showtime);
    }

    @Override
    public ShowtimeResponse create(ShowtimeRequest request) {
        Movie movie = movieRepository.findById(request.getMovieId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found"));
        Room room = roomRepository.findById(request.getRoomId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));

        validateTimeRange(request.getStartTime(), request.getEndTime());
        validateFormat(request, room);

        if (showtimeRepository.existsOverlappingInRoom(room.getId(), request.getStartTime(), request.getEndTime(), null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Showtime overlaps with an existing showtime in this room");
        }

        Showtime showtime = Showtime.builder()
                .movie(movie)
                .room(room)
                .startTime(request.getStartTime())
                .endTime(request.getEndTime())
                .basePrice(request.getBasePrice())
                .format(request.getFormat() != null ? request.getFormat() : com.movie.ticket_booking.entity.enums.MovieFormat.TWO_D)
                .languageMode(request.getLanguageMode() != null ? request.getLanguageMode() : com.movie.ticket_booking.entity.enums.LanguageMode.SUB)
                .build();

        Showtime saved = showtimeRepository.saveAndFlush(showtime);
        // Without inventory rows nothing about this showtime could be held, sold or shown on the seat map.
        inventory.initializeShowtime(saved.getId());
        return toResponse(saved);
    }

    @Override
    public ShowtimeResponse update(Long id, ShowtimeRequest request) {
        Showtime showtime = showtimeRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Showtime not found"));

        Movie movie = movieRepository.findById(request.getMovieId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found"));
        Room room = roomRepository.findById(request.getRoomId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Room not found"));

        if (showtime.getStatus() == com.movie.ticket_booking.entity.enums.ShowtimeStatus.CANCELLED) {
            throw new BusinessException(HttpStatus.CONFLICT, "SHOWTIME_CANCELLED", "A cancelled showtime cannot be changed");
        }
        validateTimeRange(request.getStartTime(), request.getEndTime());
        validateFormat(request, room);

        if (showtimeRepository.existsOverlappingInRoom(room.getId(), request.getStartTime(), request.getEndTime(), id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Showtime overlaps with an existing showtime in this room");
        }

        boolean roomChanged = showtime.getRoom() == null || !room.getId().equals(showtime.getRoom().getId());
        if (roomChanged) {
            requireUnused(id);
            inventory.removeShowtime(id);
        }
        showtime.setMovie(movie);
        showtime.setRoom(room);
        showtime.setStartTime(request.getStartTime());
        showtime.setEndTime(request.getEndTime());
        showtime.setBasePrice(request.getBasePrice());
        if (request.getFormat() != null) showtime.setFormat(request.getFormat());
        if (request.getLanguageMode() != null) showtime.setLanguageMode(request.getLanguageMode());

        Showtime saved = showtimeRepository.saveAndFlush(showtime);
        if (roomChanged) {
            inventory.initializeShowtime(saved.getId());
        }
        return toResponse(saved);
    }

    @Override
    public void delete(Long id) {
        if (!showtimeRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Showtime not found");
        }
        requireUnused(id);
        inventory.removeShowtime(id);
        showtimeRepository.deleteById(id);
    }

    /** A showtime that anyone has booked can no longer change room or disappear (cancellation is a refund flow). */
    private void requireUnused(Long showtimeId) {
        if (bookingRepository.existsByShowtimeId(showtimeId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "SHOWTIME_IN_USE", "The showtime already has bookings");
        }
    }

    private void validateTimeRange(Instant start, Instant end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startTime must be before endTime");
        }
    }

    private void validateFormat(ShowtimeRequest request, Room room) {
        if (request.getFormat() == com.movie.ticket_booking.entity.enums.MovieFormat.IMAX
                && room.getRoomType() != com.movie.ticket_booking.entity.enums.RoomType.IMAX) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_FORMAT", "An IMAX showtime needs an IMAX room");
        }
    }

    private ShowtimeResponse toResponse(Showtime showtime) {
        Movie movie = showtime.getMovie();
        Room room = showtime.getRoom();

        MovieSummaryResponse movieSummary = movie == null ? null : MovieSummaryResponse.builder()
                .id(movie.getId())
                .title(movie.getTitle())
                .slug(movie.getSlug())
                .posterUrl(movie.getPosterUrl())
                .build();

        RoomSummaryResponse roomSummary = room == null ? null : RoomSummaryResponse.builder()
                .id(room.getId())
                .code(room.getCode())
                .name(room.getName())
                .build();

        com.movie.ticket_booking.entity.Cinema cinema = room == null ? null : room.getCinema();
        com.movie.ticket_booking.dto.CinemaSummaryResponse cinemaSummary = cinema == null ? null
                : new com.movie.ticket_booking.dto.CinemaSummaryResponse(cinema.getId(), cinema.getName(), cinema.getCity(), cinema.getAddress());

        return ShowtimeResponse.builder()
                .id(showtime.getId())
                .movie(movieSummary)
                .room(roomSummary)
                .cinema(cinemaSummary)
                .startTime(showtime.getStartTime())
                .endTime(showtime.getEndTime())
                .basePrice(showtime.getBasePrice())
                .format(showtime.getFormat())
                .languageMode(showtime.getLanguageMode())
                .status(showtime.getStatus().name())
                .cancelReason(showtime.getCancelReason())
                .createdAt(showtime.getCreatedAt())
                .updatedAt(showtime.getUpdatedAt())
                .build();
    }
}
