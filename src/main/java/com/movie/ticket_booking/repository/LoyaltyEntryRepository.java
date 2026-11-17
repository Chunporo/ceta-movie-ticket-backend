package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.LoyaltyEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface LoyaltyEntryRepository extends JpaRepository<LoyaltyEntry, Long> {
    String LIVE = """
            (l.reason = com.movie.ticket_booking.entity.enums.LoyaltyReason.ADJUSTMENT
             or (l.reason = com.movie.ticket_booking.entity.enums.LoyaltyReason.EARN
                 and b.status = com.movie.ticket_booking.entity.enums.BookingStatus.PAID)
             or (l.reason = com.movie.ticket_booking.entity.enums.LoyaltyReason.REDEEM
                 and b.status in (com.movie.ticket_booking.entity.enums.BookingStatus.PENDING, com.movie.ticket_booking.entity.enums.BookingStatus.PAID)))
            """;

    /**
     * Spendable points. Earned points count while their booking is PAID, spent points while theirs is PENDING or PAID, so a
     * cancelled, expired or refunded booking gives back what it spent and loses what it earned, with no ledger rewrites.
     * {@code excludeBookingId} leaves out that booking's own spending (0 for none) when it is being edited.
     */
    @Query("select coalesce(sum(l.delta), 0) from LoyaltyEntry l left join Booking b on b.id = l.bookingId "
            + "where l.userId = :userId and (l.bookingId is null or l.bookingId <> :excludeBookingId) and " + LIVE)
    long balance(@Param("userId") Long userId, @Param("excludeBookingId") Long excludeBookingId);

    /** Points ever earned and still standing: the tier is judged on this, spending does not lower it. */
    @Query("select coalesce(sum(l.delta), 0) from LoyaltyEntry l left join Booking b on b.id = l.bookingId "
            + "where l.userId = :userId and l.delta > 0 and " + LIVE)
    long lifetimeEarned(@Param("userId") Long userId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from LoyaltyEntry l where l.bookingId = :bookingId and l.reason = com.movie.ticket_booking.entity.enums.LoyaltyReason.REDEEM")
    int deleteRedeemForBooking(@Param("bookingId") Long bookingId);

    boolean existsByBookingIdAndReason(Long bookingId, com.movie.ticket_booking.entity.enums.LoyaltyReason reason);
}
