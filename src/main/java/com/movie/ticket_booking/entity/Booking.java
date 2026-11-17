package com.movie.ticket_booking.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.movie.ticket_booking.entity.enums.BookingStatus;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(
        name = "booking"
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
        @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "showtime_id", nullable = false)
        @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private Showtime showtime;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private BookingStatus status = BookingStatus.PENDING;

    @Column(nullable = false)
    @Builder.Default
    private Long totalAmount = 0L;

    /** Part of {@code totalAmount} that is snacks, frozen at creation. */
    @org.hibernate.annotations.ColumnDefault("0")
    @Column(nullable = false)
    @Builder.Default
    private Long comboAmount = 0L;
    /** Voucher discount, frozen at creation; the total is seats + combos - discount. */
    @org.hibernate.annotations.ColumnDefault("0")
    @Column(nullable = false)
    @Builder.Default
    private Long discountAmount = 0L;
    /** CETA points spent on this booking and what they were worth in VND, frozen with the total. */
    @org.hibernate.annotations.ColumnDefault("0")
    @Column(nullable = false)
    @Builder.Default
    private Long pointsAmount = 0L;
    @org.hibernate.annotations.ColumnDefault("0")
    @Column(nullable = false)
    @Builder.Default
    private Integer pointsUsed = 0;
    @Column(nullable = false)
    private Instant holdExpiresAt;

    private Instant paidAt;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
        @JsonIgnore
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    @Builder.Default
    private List<Ticket> tickets = new ArrayList<>();

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

}
