package com.movie.ticket_booking.service;

import com.movie.ticket_booking.entity.PricingPolicy;
import com.movie.ticket_booking.entity.Room;
import com.movie.ticket_booking.entity.Seat;
import com.movie.ticket_booking.entity.Showtime;
import com.movie.ticket_booking.repository.PricingPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PricingService {

    private final PricingPolicyRepository pricingPolicyRepository;

    public BigDecimal calculatePrice(Showtime showtime, Seat seat) {
        BigDecimal finalPrice = showtime.getBasePrice();
        Room room = showtime.getRoom();
        
        // 1. Apply Room Type Multiplier
        finalPrice = applyRoomMultiplier(finalPrice, room.getRoomType());
        
        // 2. Apply Seat Type Surcharge
        finalPrice = applySeatSurcharge(finalPrice, seat.getType());
        
        // 3. Apply Time-based Surcharge (e.g., Weekends)
        finalPrice = applyTimeSurcharge(finalPrice, showtime.getStartTime().atZone(ZoneId.systemDefault()).getDayOfWeek());
        
        return finalPrice;
    }

    private BigDecimal applyRoomMultiplier(BigDecimal price, com.movie.ticket_booking.entity.enums.RoomType type) {
        // In a real app, fetch from pricingPolicyRepository
        // For now, hardcode some enterprise defaults
        BigDecimal multiplier = switch (type) {
            case IMAX -> new BigDecimal("1.5");
            case FOUR_DX -> new BigDecimal("1.8");
            case GOLD_CLASS -> new BigDecimal("2.5");
            case PREMIUM -> new BigDecimal("1.2");
            default -> BigDecimal.ONE;
        };
        return price.multiply(multiplier);
    }

    @SuppressWarnings("deprecation")
    private BigDecimal applySeatSurcharge(BigDecimal price, com.movie.ticket_booking.entity.enums.SeatType type) {
        BigDecimal surcharge = switch (type) {
            case VIP -> new BigDecimal("20000");
            case SWEETBOX -> new BigDecimal("40000");
            case DELUXE -> new BigDecimal("30000");
            case STANDARD, NORMAL, WHEELCHAIR -> BigDecimal.ZERO;
        };
        return price.add(surcharge);
    }

    private BigDecimal applyTimeSurcharge(BigDecimal price, DayOfWeek day) {
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return price.multiply(new BigDecimal("1.15")); // 15% weekend surcharge
        }
        return price;
    }
}
