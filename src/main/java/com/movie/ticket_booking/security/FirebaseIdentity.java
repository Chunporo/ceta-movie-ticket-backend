package com.movie.ticket_booking.security;

import java.time.Instant;

/**
 * What a verified Firebase ID token says about the caller. {@code authTime} is when the person last actually signed in
 * (not when the token was minted), or null when the token does not say.
 */
public record FirebaseIdentity(String uid, String email, boolean emailVerified, String name, String phone, Instant authTime) {
    public FirebaseIdentity(String uid, String email, boolean emailVerified, String name, String phone) {
        this(uid, email, emailVerified, name, phone, null);
    }

    public FirebaseIdentity(String uid, String email, boolean emailVerified, String name) {
        this(uid, email, emailVerified, name, null, null);
    }
}
