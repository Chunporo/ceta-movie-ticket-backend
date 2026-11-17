package com.movie.ticket_booking.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.movie.ticket_booking.entity.enums.ShowtimeStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "showtime",
        indexes = {
                @Index(name = "idx_showtimes_movie", columnList = "movie_id"),
                @Index(name = "idx_showtimes_room", columnList = "room_id"),
                @Index(name = "idx_showtimes_start_time", columnList = "start_time")
        }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Showtime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "movie_id", nullable = false)
        @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Movie movie;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
        @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Room room;

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    @Column(name = "end_time", nullable = false)
    private Instant endTime;

    @Column(nullable = false, precision = 12, scale = 2)
    private BigDecimal basePrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private com.movie.ticket_booking.entity.enums.MovieFormat format = com.movie.ticket_booking.entity.enums.MovieFormat.TWO_D;

    @Enumerated(EnumType.STRING)
    @Column(name = "language_mode", nullable = false, length = 10)
    @Builder.Default
    private com.movie.ticket_booking.entity.enums.LanguageMode languageMode = com.movie.ticket_booking.entity.enums.LanguageMode.SUB;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ShowtimeStatus status = ShowtimeStatus.SCHEDULED;

    private Instant cancelledAt;

    @Column(length = 500)
    private String cancelReason;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;
}
