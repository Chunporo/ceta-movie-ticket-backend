package com.movie.ticket_booking.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/** A promotion the customer saved to their voucher wallet. */
@Entity
@Table(name = "user_voucher")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserVoucher {
    @EmbeddedId
    private UserVoucherId id;

    @Column(nullable = false)
    private Instant savedAt;
}
