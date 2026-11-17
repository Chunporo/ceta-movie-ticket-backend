package com.movie.ticket_booking.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class MovieReminderId implements Serializable {
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "movie_id", nullable = false)
    private UUID movieId;
}
