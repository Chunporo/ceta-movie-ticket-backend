package com.movie.ticket_booking.repository;

import com.movie.ticket_booking.entity.PricingPolicy;
import com.movie.ticket_booking.entity.enums.RoomType;
import com.movie.ticket_booking.entity.enums.SeatType;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PricingPolicyRepository extends JpaRepository<PricingPolicy, Long> {
    Optional<PricingPolicy> findByRoomTypeAndActiveTrue(RoomType roomType);
    Optional<PricingPolicy> findBySeatTypeAndActiveTrue(SeatType seatType);
}
