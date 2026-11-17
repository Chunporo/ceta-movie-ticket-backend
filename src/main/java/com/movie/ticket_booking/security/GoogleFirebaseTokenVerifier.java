package com.movie.ticket_booking.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/** Checks Firebase ID tokens against Google's public keys: RS256, issuer and audience = the Firebase project. */
@Component
public class GoogleFirebaseTokenVerifier implements FirebaseTokenVerifier {

    private static final String JWK_URI =
            "https://www.googleapis.com/service_accounts/v1/jwk/securetoken@system.gserviceaccount.com";

    private final JwtDecoder decoder;

    public GoogleFirebaseTokenVerifier(@Value("${app.firebase.project-id}") String projectId) {
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(JWK_URI).build();
        OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null && jwt.getAudience().contains(projectId)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Wrong audience", null));
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer("https://securetoken.google.com/" + projectId), audience));
        this.decoder = nimbus;
    }

    @Override
    public FirebaseIdentity verify(String idToken) {
        Jwt jwt = decoder.decode(idToken);
        String uid = jwt.getSubject();
        if (uid == null || uid.isBlank()) {
            throw new JwtException("Token has no subject");
        }
        return new FirebaseIdentity(uid, jwt.getClaimAsString("email"),
                Boolean.TRUE.equals(jwt.getClaim("email_verified")), jwt.getClaimAsString("name"), jwt.getClaimAsString("phone_number"),
                authTime(jwt.getClaim("auth_time")));
    }

    /** {@code auth_time} is epoch seconds in a Firebase token; the decoder may hand it over as a number or an Instant. */
    private static java.time.Instant authTime(Object claim) {
        if (claim instanceof java.time.Instant instant) return instant;
        if (claim instanceof Number seconds) return java.time.Instant.ofEpochSecond(seconds.longValue());
        return null;
    }
}
