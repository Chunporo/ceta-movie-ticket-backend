package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.ReviewRequest;
import com.movie.ticket_booking.dto.ReviewResponse;
import com.movie.ticket_booking.dto.booking.PageResponse;
import com.movie.ticket_booking.entity.MovieReview;
import com.movie.ticket_booking.entity.User;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.MovieRepository;
import com.movie.ticket_booking.repository.MovieReviewRepository;
import com.movie.ticket_booking.repository.TicketRepository;
import com.movie.ticket_booking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {
    private final MovieReviewRepository reviews;
    private final MovieRepository movies;
    private final TicketRepository tickets;
    private final UserRepository users;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<ReviewResponse> list(UUID movieId, String requesterEmail, int page, int size) {
        requireMovie(movieId);
        Long me = requesterEmail == null || requesterEmail.isBlank() ? null
                : users.findByEmail(requesterEmail.trim().toLowerCase()).map(User::getId).orElse(null);
        Page<MovieReview> result = reviews.findByMovieIdOrderByIdDesc(movieId, PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50)));
        return new PageResponse<>(result.getContent().stream().map(r -> toResponse(r, me)).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public ReviewResponse mine(String requesterEmail, UUID movieId) {
        User user = findUser(requesterEmail);
        return reviews.findByMovieIdAndUserId(movieId, user.getId()).map(r -> toResponse(r, user.getId()))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "REVIEW_NOT_FOUND", "You have not reviewed this movie"));
    }

    @Override
    @Transactional
    public ReviewResponse save(String requesterEmail, UUID movieId, ReviewRequest request) {
        User user = findUser(requesterEmail);
        requireMovie(movieId);
        if (!tickets.hasWatched(user.getId(), movieId, Instant.now())) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "REVIEW_NOT_ALLOWED", "Only customers who have watched the movie can review it");
        }
        String comment = request.comment() == null || request.comment().isBlank() ? null : request.comment().trim();
        MovieReview review = reviews.findByMovieIdAndUserId(movieId, user.getId())
                .orElseGet(() -> MovieReview.builder().movieId(movieId).userId(user.getId()).build());
        review.setRating(request.rating());
        review.setComment(comment);
        return toResponse(reviews.saveAndFlush(review), user.getId());
    }

    @Override
    @Transactional
    public void delete(String requesterEmail, UUID movieId) {
        User user = findUser(requesterEmail);
        reviews.findByMovieIdAndUserId(movieId, user.getId()).ifPresent(reviews::delete);
    }

    private void requireMovie(UUID movieId) {
        if (!movies.existsById(movieId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Movie not found");
        }
    }

    private User findUser(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return users.findByEmail(email.trim().toLowerCase())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
    }

    private ReviewResponse toResponse(MovieReview r, Long viewerId) {
        String name = users.findById(r.getUserId()).map(User::getUsername).orElse("");
        return new ReviewResponse(r.getId(), r.getRating(), r.getComment(), mask(name), r.getUserId().equals(viewerId), r.getCreatedAt(), r.getUpdatedAt());
    }

    /** "Nguyen Van A" -> "Ng***"; reviews are public, so only the first letters of a username are shown. */
    static String mask(String name) {
        if (name == null || name.isBlank()) return "***";
        String trimmed = name.trim();
        return trimmed.substring(0, Math.min(2, trimmed.length())) + "***";
    }
}
