package com.movie.ticket_booking.security;

/** Verifies a Firebase ID token (signature, issuer, audience, expiry). Seam so tests never call Google. */
public interface FirebaseTokenVerifier {

    /** @throws org.springframework.security.oauth2.jwt.JwtException when the token is not valid for this project */
    FirebaseIdentity verify(String idToken);
}
