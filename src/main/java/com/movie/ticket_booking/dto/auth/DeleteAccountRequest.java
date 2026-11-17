package com.movie.ticket_booking.dto.auth;

import jakarta.validation.constraints.Size;

/**
 * An account with its own password confirms with it; an account that signs in through Firebase has none, so it
 * confirms with a Firebase ID token from a sign-in made just now (the app asks the customer to sign in again).
 */
public record DeleteAccountRequest(@Size(max = 100) String password, @Size(max = 4096) String idToken) {
    public DeleteAccountRequest(String password) {
        this(password, null);
    }
}
