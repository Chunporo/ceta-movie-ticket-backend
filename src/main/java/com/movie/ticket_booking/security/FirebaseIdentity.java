package com.movie.ticket_booking.security;

/** What a verified Firebase ID token says about the caller. */
public record FirebaseIdentity(String uid, String email, boolean emailVerified, String name) {
}
