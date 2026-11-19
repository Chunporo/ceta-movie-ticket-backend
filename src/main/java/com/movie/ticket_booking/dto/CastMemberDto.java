package com.movie.ticket_booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** An actor on a movie: name, the character they play and a profile photo URL (role and photo are optional). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CastMemberDto {
    @NotBlank
    @Size(max = 200)
    private String name;

    @Size(max = 200)
    private String role;

    @Size(max = 500)
    private String photoUrl;
}
