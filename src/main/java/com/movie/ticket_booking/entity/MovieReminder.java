package com.movie.ticket_booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** "Remind me when tickets go on sale": removed once the customer has been told. */
@Entity
@Table(name = "movie_reminder")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MovieReminder {
    @EmbeddedId
    private MovieReminderId id;

    @Column(nullable = false)
    private Instant createdAt;
}
