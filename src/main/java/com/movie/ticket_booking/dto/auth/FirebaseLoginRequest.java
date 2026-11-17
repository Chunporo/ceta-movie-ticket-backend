package com.movie.ticket_booking.dto.auth;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FirebaseLoginRequest {

    /** Firebase ID token from FirebaseUser.getIdToken(). */
    @NotBlank
    private String idToken;
}
