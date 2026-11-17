package com.movie.ticket_booking.event;

/** Carries the raw reset token to the after-commit mail listener; only its hash is ever stored. */
public record PasswordResetRequestedEvent(Long userId, String token) {
    @Override
    public String toString() {
        return "PasswordResetRequestedEvent[userId=" + userId + "]"; // never log the token
    }
}
