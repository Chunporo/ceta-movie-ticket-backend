package com.movie.ticket_booking.service;

import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.*;
import com.movie.ticket_booking.repository.ShowtimeRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** "From 85.000d" on the movie page: the cheapest upcoming, not cancelled showtime. */
@DataJpaTest(showSql = false)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MovieFromPriceTest {
    @Autowired private ShowtimeRepository showtimes;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private UUID movieId, otherId;
    private long roomId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Price test").totalRows(1).totalColumns(1).build());
            Movie movie = em.persist(Movie.builder().title("A").slug(suffix).posterUrl("https://example.invalid/p").ageRating(AgeRating.P)
                    .status(MovieStatus.NOW_SHOWING).duration(100).build());
            Movie other = em.persist(Movie.builder().title("B").slug("b" + suffix).posterUrl("https://example.invalid/p").ageRating(AgeRating.P)
                    .status(MovieStatus.NOW_SHOWING).duration(100).build());
            show(movie, room, 3, 120_000, ShowtimeStatus.SCHEDULED);
            show(movie, room, 5, 85_000, ShowtimeStatus.SCHEDULED);
            show(movie, room, 6, 50_000, ShowtimeStatus.CANCELLED);   // cheaper but cancelled
            show(movie, room, -48, 40_000, ShowtimeStatus.SCHEDULED); // cheaper but already over
            em.flush();
            roomId = room.getId();
            movieId = movie.getId();
            otherId = other.getId();
        });
    }

    private void show(Movie movie, Room room, int hoursFromNow, int price, ShowtimeStatus status) {
        Instant start = Instant.now().plus(hoursFromNow, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS);
        em.persist(Showtime.builder().movie(movie).room(room).startTime(start).endTime(start.plusSeconds(3600))
                .basePrice(BigDecimal.valueOf(price)).status(status).build());
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM showtime WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM rooms WHERE id=?", roomId);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.remove(em.find(Movie.class, movieId));
            em.remove(em.find(Movie.class, otherId));
        });
    }

    @Test
    void theCheapestUpcomingShowtimeWinsAndCancelledOrPastOnesAreIgnored() {
        var stats = showtimes.minPricesByMovie(Instant.now());
        assertEquals(85_000L, stats.stream().filter(s -> s.getMovieId().equals(movieId)).findFirst().orElseThrow().getMinPrice().longValue());
    }

    @Test
    void aMovieWithoutUpcomingShowtimesHasNoPrice() {
        assertTrue(showtimes.minPricesByMovie(Instant.now()).stream().noneMatch(s -> s.getMovieId().equals(otherId)));
    }
}
