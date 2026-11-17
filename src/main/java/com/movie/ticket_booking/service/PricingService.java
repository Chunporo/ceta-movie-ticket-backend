package com.movie.ticket_booking.service;

import com.movie.ticket_booking.entity.Seat;
import com.movie.ticket_booking.entity.Showtime;
import com.movie.ticket_booking.entity.enums.MovieFormat;
import com.movie.ticket_booking.entity.enums.RoomType;
import com.movie.ticket_booking.entity.enums.SeatType;
import com.movie.ticket_booking.repository.PricingPolicyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.ZoneId;

/**
 * Price of one seat = showtime base price + seat-type surcharge + format add-on, times the room and weekend factors.
 * With the app's numbers: standard 85.000, VIP 105.000, a couple seat 110.000 (220.000 per pair), +20.000 for 3D
 * and +50.000 for IMAX per seat.
 */
@Service
@RequiredArgsConstructor
public class PricingService {
    private final PricingPolicyRepository pricingPolicyRepository;

    public BigDecimal calculatePrice(Showtime showtime, Seat seat) {
        BigDecimal price = showtime.getBasePrice();
        price = price.add(seatSurcharge(seat.getType()));
        price = price.add(formatAddOn(showtime.getFormat()));
        price = price.multiply(roomMultiplier(showtime.getRoom().getRoomType()));
        price = applyTimeSurcharge(price, showtime.getStartTime().atZone(ZoneId.systemDefault()).getDayOfWeek());
        return price;
    }

    @SuppressWarnings("deprecation")
    private BigDecimal seatSurcharge(SeatType type) {
        return switch (type) {
            case VIP -> new BigDecimal("20000");
            case SWEETBOX -> new BigDecimal("25000");
            case DELUXE -> new BigDecimal("30000");
            case STANDARD, NORMAL, WHEELCHAIR -> BigDecimal.ZERO;
        };
    }

    private BigDecimal formatAddOn(MovieFormat format) {
        if (format == null) return BigDecimal.ZERO;
        return switch (format) {
            case TWO_D -> BigDecimal.ZERO;
            case THREE_D -> new BigDecimal("20000");
            case IMAX -> new BigDecimal("50000");
        };
    }

    /** IMAX is priced by the showtime's format, so only the other premium rooms keep a multiplier. */
    private BigDecimal roomMultiplier(RoomType type) {
        return switch (type) {
            case FOUR_DX -> new BigDecimal("1.8");
            case GOLD_CLASS -> new BigDecimal("2.5");
            case PREMIUM -> new BigDecimal("1.2");
            default -> BigDecimal.ONE;
        };
    }

    private BigDecimal applyTimeSurcharge(BigDecimal price, DayOfWeek day) {
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
            return price.multiply(new BigDecimal("1.15")); // 15% weekend surcharge
        }
        return price;
    }
}
