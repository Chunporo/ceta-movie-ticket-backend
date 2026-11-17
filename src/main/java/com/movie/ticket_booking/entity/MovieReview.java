package com.movie.ticket_booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.util.UUID;

/** One review per customer and movie. {@code rating} is 1..10 (the app shows it as half-stars out of 5). */
@Entity
@Table(name = "movie_review", uniqueConstraints = @UniqueConstraint(name = "uk_review_movie_user", columnNames = {"movie_id", "user_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MovieReview {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "movie_id", nullable = false)
    private UUID movieId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private int rating;

    @Column(length = 1000)
    private String comment;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;
}
