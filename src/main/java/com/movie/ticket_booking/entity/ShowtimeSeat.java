package com.movie.ticket_booking.entity;

import com.movie.ticket_booking.entity.enums.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;

@Entity
@Table(name = "showtime_seat")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShowtimeSeat {
    @EmbeddedId
    private ShowtimeSeatId id;
    @MapsId("showtimeId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "showtime_id", nullable = false)
    private Showtime showtime;
    @MapsId("seatId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "seat_id", nullable = false)
    private Seat seat;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private SeatInventoryStatus status = SeatInventoryStatus.AVAILABLE;
    private Instant heldUntil;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private Booking booking;
    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

}
