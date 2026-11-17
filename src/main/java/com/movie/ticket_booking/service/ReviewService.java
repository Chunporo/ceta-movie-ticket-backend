package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.ReviewRequest;
import com.movie.ticket_booking.dto.ReviewResponse;
import com.movie.ticket_booking.dto.booking.PageResponse;

import java.util.UUID;

public interface ReviewService {
    /** Newest first. {@code requesterEmail} (may be null) only marks which review is the caller's own. */
    PageResponse<ReviewResponse> list(UUID movieId, String requesterEmail, int page, int size);

    /** The caller's own review of the movie (404 if there is none). */
    ReviewResponse mine(String requesterEmail, UUID movieId);

    /** Creates or replaces the caller's review. Only someone who has reached the start of a show they paid for may review. */
    ReviewResponse save(String requesterEmail, UUID movieId, ReviewRequest request);

    void delete(String requesterEmail, UUID movieId);
}
