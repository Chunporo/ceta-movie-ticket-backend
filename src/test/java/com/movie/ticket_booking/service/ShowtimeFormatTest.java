package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.ShowtimeRequest;
import com.movie.ticket_booking.dto.ShowtimeResponse;
import com.movie.ticket_booking.entity.Movie;
import com.movie.ticket_booking.entity.Room;
import com.movie.ticket_booking.entity.Showtime;
import com.movie.ticket_booking.entity.enums.LanguageMode;
import com.movie.ticket_booking.entity.enums.MovieFormat;
import com.movie.ticket_booking.entity.enums.RoomType;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.MovieRepository;
import com.movie.ticket_booking.repository.RoomRepository;
import com.movie.ticket_booking.repository.ShowtimeRepository;
import com.movie.ticket_booking.service.inventory.InventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ShowtimeFormatTest {
    private ShowtimeRepository showtimes;
    private RoomRepository rooms;
    private MovieRepository movies;
    private ShowtimeServiceImpl service;
    private final UUID movieId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        showtimes = mock(ShowtimeRepository.class);
        rooms = mock(RoomRepository.class);
        movies = mock(MovieRepository.class);
        service = new ShowtimeServiceImpl(showtimes, movies, rooms, mock(com.movie.ticket_booking.repository.BookingRepository.class), mock(InventoryService.class));
        when(movies.findById(movieId)).thenReturn(Optional.of(Movie.builder().id(movieId).title("Dune").build()));
        when(showtimes.saveAndFlush(any(Showtime.class))).thenAnswer(i -> {
            Showtime s = i.getArgument(0);
            s.setId(1L);
            return s;
        });
    }

    private ShowtimeRequest request(MovieFormat format, LanguageMode mode) {
        return ShowtimeRequest.builder().movieId(movieId).roomId(7L)
                .startTime(Instant.parse("2026-11-20T10:00:00Z")).endTime(Instant.parse("2026-11-20T12:00:00Z"))
                .basePrice(new BigDecimal("85000")).format(format).languageMode(mode).build();
    }

    private void roomOfType(RoomType type) {
        when(rooms.findById(7L)).thenReturn(Optional.of(Room.builder().id(7L).code("R").name("R").roomType(type).build()));
    }

    @Test
    void aShowtimeWithoutFormatOrModeIsTwoDSubtitled() {
        roomOfType(RoomType.STANDARD);
        ShowtimeResponse created = service.create(request(null, null));
        assertThat(created.getFormat()).isEqualTo(MovieFormat.TWO_D);
        assertThat(created.getLanguageMode()).isEqualTo(LanguageMode.SUB);
    }

    @Test
    void formatAndModeAreStored() {
        roomOfType(RoomType.STANDARD);
        ShowtimeResponse created = service.create(request(MovieFormat.THREE_D, LanguageMode.DUB));
        assertThat(created.getFormat()).isEqualTo(MovieFormat.THREE_D);
        assertThat(created.getLanguageMode()).isEqualTo(LanguageMode.DUB);
    }

    @Test
    void imaxNeedsAnImaxRoom() {
        roomOfType(RoomType.STANDARD);
        assertThatThrownBy(() -> service.create(request(MovieFormat.IMAX, null)))
                .isInstanceOf(BusinessException.class).hasMessageContaining("IMAX room");
        verify(showtimes, never()).saveAndFlush(any());
    }

    @Test
    void imaxInAnImaxRoomIsAccepted() {
        roomOfType(RoomType.IMAX);
        assertThat(service.create(request(MovieFormat.IMAX, null)).getFormat()).isEqualTo(MovieFormat.IMAX);
    }

    @Test
    void getAllFiltersByFormat() {
        Room room = Room.builder().id(7L).code("R").name("R").roomType(RoomType.STANDARD).build();
        Movie movie = Movie.builder().id(movieId).title("Dune").build();
        Showtime a = Showtime.builder().id(1L).movie(movie).room(room).format(MovieFormat.TWO_D)
                .startTime(Instant.parse("2026-11-20T10:00:00Z")).endTime(Instant.parse("2026-11-20T12:00:00Z")).basePrice(BigDecimal.ONE).build();
        Showtime b = Showtime.builder().id(2L).movie(movie).room(room).format(MovieFormat.THREE_D)
                .startTime(Instant.parse("2026-11-20T14:00:00Z")).endTime(Instant.parse("2026-11-20T16:00:00Z")).basePrice(BigDecimal.ONE).build();
        when(showtimes.findAll()).thenReturn(java.util.List.of(a, b));

        assertThat(service.getAll(null, null, null, MovieFormat.THREE_D, null, null)).extracting(ShowtimeResponse::getId).containsExactly(2L);
        assertThat(service.getAll(null, null, null, null, null, null)).hasSize(2);
    }
}
