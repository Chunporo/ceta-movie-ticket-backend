package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.Promotion;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface PromotionRepository extends JpaRepository<Promotion, Long> {
    Optional<Promotion> findByCode(String code);

    boolean existsByCode(String code);

    /** Offers to show: active and not past their end date. */
    @Query("select p from Promotion p where p.active = true and (p.validUntil is null or p.validUntil > :now) order by p.sortOrder, p.id")
    List<Promotion> findListed(@Param("now") Instant now);

    /** Serialises two bookings of one customer that both want a once-only voucher. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Promotion p where p.id = :id")
    Optional<Promotion> findByIdForUpdate(@Param("id") Long id);
}
