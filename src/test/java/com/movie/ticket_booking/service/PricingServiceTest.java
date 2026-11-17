package com.movie.ticket_booking.service;

import com.movie.ticket_booking.entity.Room;
import com.movie.ticket_booking.entity.Seat;
import com.movie.ticket_booking.entity.Showtime;
import com.movie.ticket_booking.entity.enums.MovieFormat;
import com.movie.ticket_booking.entity.enums.RoomType;
import com.movie.ticket_booking.entity.enums.SeatType;
import com.movie.ticket_booking.repository.PricingPolicyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PricingServiceTest {
    private final PricingService pricing = new PricingService(mock(PricingPolicyRepository.class));

    // Noon UTC on a Wednesday / Saturday: the same weekday or weekend in every time zone.
    private static final Instant WEEKDAY = Instant.parse("2026-11-18T12:00:00Z");
    private static final Instant SATURDAY = Instant.parse("2026-11-21T12:00:00Z");

    private BigDecimal price(Instant start, MovieFormat format, RoomType room, SeatType seat) {
        Showtime showtime = Showtime.builder()
                .startTime(start).basePrice(new BigDecimal("85000")).format(format)
                .room(Room.builder().roomType(room).build()).build();
        return pricing.calculatePrice(showtime, Seat.builder().type(seat).build());
    }

    @ParameterizedTest(name = "{0} seat in {1} on a weekday costs {2}")
    @CsvSource({
            "STANDARD, TWO_D,  85000",
            "VIP,      TWO_D,  105000",
            "SWEETBOX, TWO_D,  110000",
            "STANDARD, THREE_D,105000",
            "VIP,      THREE_D,125000",
            "STANDARD, IMAX,   135000",
            "VIP,      IMAX,   155000",
            "SWEETBOX, IMAX,   160000",
    })
    void weekdayPricesMatchTheAppDesign(SeatType seat, MovieFormat format, long expected) {
        RoomType room = format == MovieFormat.IMAX ? RoomType.IMAX : RoomType.STANDARD;
        assertThat(price(WEEKDAY, format, room, seat)).isEqualByComparingTo(BigDecimal.valueOf(expected));
    }

    @Test
    void aCouplePairCostsTwiceTheCoupleSeat() {
        BigDecimal pair = price(WEEKDAY, MovieFormat.TWO_D, RoomType.STANDARD, SeatType.SWEETBOX).multiply(BigDecimal.TWO);
        assertThat(pair).isEqualByComparingTo("220000");
    }

    @Test
    void weekendsAddFifteenPercent() {
        assertThat(price(SATURDAY, MovieFormat.TWO_D, RoomType.STANDARD, SeatType.STANDARD)).isEqualByComparingTo("97750");
    }

    @Test
    void imaxIsPricedByFormatNotByTheRoomMultiplier() {
        // An IMAX room showing a 2D film no longer costs 1.5x.
        assertThat(price(WEEKDAY, MovieFormat.TWO_D, RoomType.IMAX, SeatType.STANDARD)).isEqualByComparingTo("85000");
    }

    @Test
    void otherPremiumRoomsKeepTheirMultiplier() {
        assertThat(price(WEEKDAY, MovieFormat.TWO_D, RoomType.GOLD_CLASS, SeatType.STANDARD)).isEqualByComparingTo("212500");
    }

    @Test
    void aShowtimeWithoutAFormatIsPricedAsTwoD() {
        assertThat(price(WEEKDAY, null, RoomType.STANDARD, SeatType.STANDARD)).isEqualByComparingTo("85000");
    }

    @Test
    void everyPriceIsAWholeNumberOfDong() {
        for (SeatType seat : new SeatType[]{SeatType.STANDARD, SeatType.VIP, SeatType.SWEETBOX}) {
            for (MovieFormat format : MovieFormat.values()) {
                BigDecimal p = price(SATURDAY, format, format == MovieFormat.IMAX ? RoomType.IMAX : RoomType.STANDARD, seat);
                assertThat(p.stripTrailingZeros().scale()).isLessThanOrEqualTo(0); // longValueExact() must not throw
            }
        }
    }
}
