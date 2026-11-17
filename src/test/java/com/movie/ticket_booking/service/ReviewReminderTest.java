package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.MovieSummaryResponse;
import com.movie.ticket_booking.dto.ReviewRequest;
import com.movie.ticket_booking.dto.ReviewResponse;
import com.movie.ticket_booking.dto.booking.PageResponse;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.*;
import com.movie.ticket_booking.event.MovieOnSaleEvent;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.MovieReviewRepository;
import com.movie.ticket_booking.service.notification.MovieOnSaleNotifier;
import com.movie.ticket_booking.service.notification.NotificationWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** B6 on H2: only people who reached a paid show may review; reminders fire once when the first showtime exists. */
@DataJpaTest(showSql = false)
@Import({ReviewServiceImpl.class, ReminderServiceImpl.class, MovieOnSaleNotifier.class, NotificationWriter.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReviewReminderTest {
    @Autowired private ReviewService reviews;
    @Autowired private ReminderService reminders;
    @Autowired private MovieOnSaleNotifier notifier;
    @Autowired private MovieReviewRepository reviewRepo;
    @Autowired private TestEntityManager em;
    @Autowired private PlatformTransactionManager transactions;
    @Autowired private JdbcTemplate jdbc;

    private String emailA, emailB;
    private long userA, userB, roomId, seatId, showtimeId;
    private UUID movieId, comingSoonId;
    private long bookingId;

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            String suffix = UUID.randomUUID().toString();
            emailA = "a" + suffix + "@example.invalid";
            emailB = "b" + suffix + "@example.invalid";
            User a = em.persist(User.builder().username("Nguyen Van A").email(emailA).passwordHash("x").build());
            User b = em.persist(User.builder().username("Bo").email(emailB).passwordHash("x").build());
            Room room = em.persist(Room.builder().code(suffix.substring(0, 12)).name("Review test").totalRows(1).totalColumns(1).build());
            Movie movie = em.persist(Movie.builder().title("Dune").slug(suffix).posterUrl("https://example.invalid/p")
                    .ageRating(AgeRating.P).status(MovieStatus.NOW_SHOWING).duration(120).build());
            Movie soon = em.persist(Movie.builder().title("Sandstorm").slug("s" + suffix).posterUrl("https://example.invalid/p")
                    .ageRating(AgeRating.P).status(MovieStatus.COMING_SOON).duration(120).build());
            Instant start = Instant.now().minus(3, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS); // already started
            Showtime showtime = em.persist(Showtime.builder().movie(movie).room(room).startTime(start)
                    .endTime(start.plusSeconds(7200)).basePrice(BigDecimal.valueOf(100000)).build());
            Seat seat = em.persist(Seat.builder().room(room).label("A1").rowNumber(1).columnNumber(1).build());
            Booking booking = em.persist(Booking.builder().user(a).showtime(showtime).status(BookingStatus.PAID)
                    .totalAmount(100000L).holdExpiresAt(Instant.now()).build());
            em.persist(Ticket.builder().booking(booking).showtime(showtime).seat(seat).price(BigDecimal.valueOf(100000)).code("T" + suffix.substring(0, 20)).build());
            em.flush();
            userA = a.getId();
            userB = b.getId();
            roomId = room.getId();
            seatId = seat.getId();
            showtimeId = showtime.getId();
            movieId = movie.getId();
            comingSoonId = soon.getId();
            bookingId = booking.getId();
        });
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM notification WHERE user_id IN (?,?)", userA, userB);
        jdbc.update("DELETE FROM movie_reminder WHERE user_id IN (?,?)", userA, userB);
        jdbc.update("DELETE FROM movie_review WHERE user_id IN (?,?)", userA, userB);
        jdbc.update("DELETE FROM tickets WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM booking WHERE showtime_id=?", showtimeId);
        jdbc.update("DELETE FROM showtime WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM seat WHERE room_id=?", roomId);
        jdbc.update("DELETE FROM rooms WHERE id=?", roomId);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            em.remove(em.find(Movie.class, movieId));
            em.remove(em.find(Movie.class, comingSoonId));
            em.remove(em.find(User.class, userA));
            em.remove(em.find(User.class, userB));
        });
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    // ---- reviews

    @Test
    void aCustomerWhoReachedAPaidShowCanReviewAndCanChangeTheirMind() {
        ReviewResponse first = reviews.save(emailA, movieId, new ReviewRequest(8, "  Great  "));
        assertEquals(8, first.rating());
        assertEquals("Great", first.comment());
        assertTrue(first.mine());

        ReviewResponse second = reviews.save(emailA, movieId, new ReviewRequest(10, null));
        assertEquals(first.id(), second.id(), "one review per customer and movie");
        assertEquals(10, second.rating());
        assertNull(second.comment());
        assertEquals(1, count("SELECT COUNT(*) FROM movie_review WHERE movie_id=?", movieId));
    }

    @Test
    void someoneWhoNeverWatchedCannotReview() {
        BusinessException e = assertThrows(BusinessException.class, () -> reviews.save(emailB, movieId, new ReviewRequest(5, "x")));
        assertEquals("REVIEW_NOT_ALLOWED", e.getCode());
        assertEquals(0, count("SELECT COUNT(*) FROM movie_review"));
    }

    @Test
    void anUnpaidOrNotYetStartedBookingDoesNotCount() {
        jdbc.update("UPDATE booking SET status='REFUNDED' WHERE id=?", bookingId);
        assertEquals("REVIEW_NOT_ALLOWED", assertThrows(BusinessException.class, () -> reviews.save(emailA, movieId, new ReviewRequest(5, null))).getCode());

        jdbc.update("UPDATE booking SET status='PAID' WHERE id=?", bookingId);
        jdbc.update("UPDATE showtime SET start_time=? WHERE id=?", java.sql.Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)), showtimeId);
        assertEquals("REVIEW_NOT_ALLOWED", assertThrows(BusinessException.class, () -> reviews.save(emailA, movieId, new ReviewRequest(5, null))).getCode(),
                "a show that has not started yet cannot have been watched");
    }

    @Test
    void aReviewIsOnlyForTheMovieThatWasWatched() {
        assertEquals("REVIEW_NOT_ALLOWED", assertThrows(BusinessException.class, () -> reviews.save(emailA, comingSoonId, new ReviewRequest(5, null))).getCode());
    }

    @Test
    void anUnknownMovieIs404() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> reviews.save(emailA, UUID.randomUUID(), new ReviewRequest(5, null)));
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> reviews.list(UUID.randomUUID(), null, 0, 20));
    }

    @Test
    void theListIsPublicMaskedNewestFirstAndFlagsTheViewersOwn() {
        reviews.save(emailA, movieId, new ReviewRequest(9, "Loved it"));
        // a second reviewer: B watched too
        jdbc.update("UPDATE booking SET user_id=? WHERE id=?", userB, bookingId);
        reviews.save(emailB, movieId, new ReviewRequest(6, "Fine"));

        PageResponse<ReviewResponse> asB = reviews.list(movieId, emailB, 0, 20);
        assertEquals(2, asB.totalItems());
        assertEquals(List.of(6, 9), asB.items().stream().map(ReviewResponse::rating).toList(), "newest first");
        assertEquals(List.of(true, false), asB.items().stream().map(ReviewResponse::mine).toList());
        assertEquals(List.of("Bo***", "Ng***"), asB.items().stream().map(ReviewResponse::author).toList());
        assertTrue(reviews.list(movieId, null, 0, 20).items().stream().noneMatch(ReviewResponse::mine));
    }

    @Test
    void mineIs404UntilReviewedAndDeleteIsIdempotent() {
        assertEquals("REVIEW_NOT_FOUND", assertThrows(BusinessException.class, () -> reviews.mine(emailA, movieId)).getCode());
        reviews.save(emailA, movieId, new ReviewRequest(7, null));
        assertEquals(7, reviews.mine(emailA, movieId).rating());
        reviews.delete(emailA, movieId);
        reviews.delete(emailA, movieId);
        assertEquals(0, count("SELECT COUNT(*) FROM movie_review"));
    }

    @Test
    void theAverageAndCountComeFromTheReviews() {
        reviews.save(emailA, movieId, new ReviewRequest(8, null));
        jdbc.update("INSERT INTO movie_review (movie_id,user_id,rating,created_at,updated_at) VALUES (?,?,?,?,?)",
                movieId, userB, 9, java.sql.Timestamp.from(Instant.now()), java.sql.Timestamp.from(Instant.now()));

        MovieReviewRepository.Stat stat = reviewRepo.statFor(movieId).orElseThrow();
        assertEquals(8.5, stat.getAverage(), 0.001);
        assertEquals(2, stat.getCount());
        assertTrue(reviewRepo.statFor(comingSoonId).isEmpty());
        assertEquals(1, reviewRepo.statsForAllMovies().stream().filter(s -> s.getMovieId().equals(movieId)).count());
        assertEquals(1, reviewRepo.countByUserId(userA));
    }

    @Test
    void usernamesAreMasked() {
        assertEquals("Ng***", ReviewServiceImpl.mask("Nguyen Van A"));
        assertEquals("A***", ReviewServiceImpl.mask("A"));
        assertEquals("***", ReviewServiceImpl.mask("  "));
        assertEquals("***", ReviewServiceImpl.mask(null));
    }

    // ---- reminders

    @Test
    void aReminderCanBeSetOnceListedAndRemoved() {
        reminders.add(emailA, comingSoonId);
        reminders.add(emailA, comingSoonId);
        assertEquals(1, count("SELECT COUNT(*) FROM movie_reminder WHERE user_id=?", userA));
        List<MovieSummaryResponse> mine = reminders.mine(emailA);
        assertEquals(List.of("Sandstorm"), mine.stream().map(MovieSummaryResponse::getTitle).toList());

        reminders.remove(emailA, comingSoonId);
        reminders.remove(emailA, comingSoonId);
        assertTrue(reminders.mine(emailA).isEmpty());
    }

    @Test
    void aMovieThatAlreadyHasShowtimesCannotBeWaitedFor() {
        BusinessException e = assertThrows(BusinessException.class, () -> reminders.add(emailA, movieId));
        assertEquals("ALREADY_ON_SALE", e.getCode());
    }

    @Test
    void aReminderOnAnUnknownMovieIs404() {
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> reminders.add(emailA, UUID.randomUUID()));
    }

    @Test
    void whenTicketsGoOnSaleEveryoneWaitingIsToldOnceAndTheRemindersAreCleared() {
        reminders.add(emailA, comingSoonId);
        reminders.add(emailB, comingSoonId);

        notifier.onSale(new MovieOnSaleEvent(comingSoonId, "Sandstorm"));

        assertEquals(1, count("SELECT COUNT(*) FROM notification WHERE user_id=? AND type='MOVIE_ON_SALE' AND movie_id=?", userA, comingSoonId));
        assertEquals(1, count("SELECT COUNT(*) FROM notification WHERE user_id=? AND type='MOVIE_ON_SALE'", userB));
        assertTrue(jdbc.queryForObject("SELECT body FROM notification WHERE user_id=?", String.class, userA).contains("Sandstorm"));
        assertEquals(0, count("SELECT COUNT(*) FROM movie_reminder WHERE movie_id=?", comingSoonId));

        notifier.onSale(new MovieOnSaleEvent(comingSoonId, "Sandstorm")); // nobody left to tell
        assertEquals(1, count("SELECT COUNT(*) FROM notification WHERE user_id=?", userA));
    }

    @Test
    void anOnSaleEventForAMovieNobodyWaitsForDoesNothing() {
        notifier.onSale(new MovieOnSaleEvent(movieId, "Dune"));
        assertEquals(0, count("SELECT COUNT(*) FROM notification WHERE user_id IN (?,?)", userA, userB));
    }
}
