package com.movie.ticket_booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "payment_transaction")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PaymentTransaction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;
    @Column(nullable = false, length = 20)
    private String provider;
    @Column(nullable = false, length = 64)
    private String txnRef;
    @Column(length = 64)
    private String providerTxn;
    @Column(nullable = false)
    private Long amount;
    @Column(nullable = false, length = 20)
    private String status;
    @JdbcTypeCode(SqlTypes.JSON)
    private String rawPayload;
    private Instant processedAt;
    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

}
