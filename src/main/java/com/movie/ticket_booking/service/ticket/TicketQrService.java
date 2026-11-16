package com.movie.ticket_booking.service.ticket;

import java.time.Instant;

/**
 * Static, signed QR per ticket (D-23): the staff web app verifies it with no database round trip.
 * Format {@code CETA1.<ticketId>.<expiryEpochSeconds>.<base64url HMAC-SHA256>}.
 */
public interface TicketQrService {
    String sign(Long ticketId, Instant expiresAt);

    /** Returns the ticket id if the payload is genuine and not expired; otherwise throws {@link InvalidQrException}. */
    Long verify(String payload);

    class InvalidQrException extends RuntimeException {
        public InvalidQrException(String message) {
            super(message);
        }
    }
}
