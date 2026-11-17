package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.BookingPromotion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface BookingPromotionRepository extends JpaRepository<BookingPromotion, Long> {
    /** How many live bookings (PENDING or PAID) of this customer use the promotion, not counting {@code excludeBookingId}. */
    @Query("""
            select count(bp) from BookingPromotion bp join Booking b on b.id = bp.bookingId
            where bp.userId = :userId and bp.promotionId = :promotionId and b.id <> :excludeBookingId
              and b.status in (com.movie.ticket_booking.entity.enums.BookingStatus.PENDING, com.movie.ticket_booking.entity.enums.BookingStatus.PAID)
            """)
    long countLiveUses(@Param("userId") Long userId, @Param("promotionId") Long promotionId, @Param("excludeBookingId") Long excludeBookingId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from BookingPromotion bp where bp.bookingId = :bookingId")
    int deleteByBookingId(@Param("bookingId") Long bookingId);
}
