package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.MovieSummaryResponse;
import com.movie.ticket_booking.entity.Movie;
import com.movie.ticket_booking.entity.MovieReminder;
import com.movie.ticket_booking.entity.MovieReminderId;
import com.movie.ticket_booking.entity.User;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.MovieReminderRepository;
import com.movie.ticket_booking.repository.MovieRepository;
import com.movie.ticket_booking.repository.ShowtimeRepository;
import com.movie.ticket_booking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReminderServiceImpl implements ReminderService {
    private final MovieReminderRepository reminders;
    private final MovieRepository movies;
    private final ShowtimeRepository showtimes;
    private final UserRepository users;

    @Override
    @Transactional
    public void add(String requesterEmail, UUID movieId) {
        User user = findUser(requesterEmail);
        if (!movies.existsById(movieId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found");
        }
        if (!showtimes.findByMovieId(movieId).isEmpty()) {
            throw new BusinessException(HttpStatus.CONFLICT, "ALREADY_ON_SALE", "Tickets for this movie are already on sale");
        }
        MovieReminderId id = new MovieReminderId(user.getId(), movieId);
        if (!reminders.existsById(id)) {
            reminders.save(MovieReminder.builder().id(id).createdAt(Instant.now()).build());
        }
    }

    @Override
    @Transactional
    public void remove(String requesterEmail, UUID movieId) {
        User user = findUser(requesterEmail);
        reminders.deleteById(new MovieReminderId(user.getId(), movieId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<MovieSummaryResponse> mine(String requesterEmail) {
        User user = findUser(requesterEmail);
        return reminders.findByIdUserIdOrderByCreatedAtDesc(user.getId()).stream()
                .map(r -> movies.findById(r.getId().getMovieId()).orElse(null))
                .filter(Objects::nonNull)
                .map(ReminderServiceImpl::summary)
                .toList();
    }

    private static MovieSummaryResponse summary(Movie m) {
        return MovieSummaryResponse.builder().id(m.getId()).title(m.getTitle()).slug(m.getSlug()).posterUrl(m.getPosterUrl()).build();
    }

    private User findUser(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return users.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }
}
