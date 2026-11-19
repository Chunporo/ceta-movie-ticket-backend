package com.movie.ticket_booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One actor of a movie, in billing order (see {@link Movie#getCast()}). Photo and character are optional. */
@Embeddable
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CastMember {
    @Column(name = "cast_name", nullable = false, length = 200)
    private String name;

    @Column(name = "character_name", length = 200)
    private String role;

    @Column(name = "profile_url", length = 500)
    private String photoUrl;

    public static CastMember named(String name) {
        return new CastMember(name, null, null);
    }
}
