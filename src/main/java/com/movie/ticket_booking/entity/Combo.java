package com.movie.ticket_booking.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/** A snack combo sold with a booking and collected at the counter. Price is whole VND. */
@Entity
@Table(name = "combo", uniqueConstraints = @UniqueConstraint(name = "uk_combo_code", columnNames = "code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Combo {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 100)
    private String nameEn;

    @Column
    private String description;

    @Column
    private String descriptionEn;

    @Column(length = 500)
    private String imageUrl;

    @Column(nullable = false)
    private Long price;

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;
}
