package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.ComboLineRequest;
import com.movie.ticket_booking.entity.Promotion;
import com.movie.ticket_booking.entity.Showtime;
import com.movie.ticket_booking.entity.enums.BookingStatus;
import com.movie.ticket_booking.entity.enums.MovieFormat;
import com.movie.ticket_booking.entity.enums.PromotionKind;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** The voucher rules without a database: discount maths and every reason a code is refused. */
class BookingExtrasCalculatorTest {
    private PromotionRepository promotions;
    private BookingPromotionRepository bookingPromotions;
    private BookingRepository bookings;
    private BookingExtrasCalculator calculator;

    // 2026-11-17 is a Tuesday; noon UTC is 19:00 in Vietnam, still Tuesday.
    private static final Instant TUESDAY = Instant.parse("2026-11-17T12:00:00Z");

    @BeforeEach
    void setUp() {
        promotions = mock(PromotionRepository.class);
        bookingPromotions = mock(BookingPromotionRepository.class);
        bookings = mock(BookingRepository.class);
        calculator = new BookingExtrasCalculator(mock(ComboRepository.class), promotions, bookingPromotions,
                mock(BookingComboRepository.class), bookings, 10, 2000, 20000L, "Asia/Ho_Chi_Minh");
    }

    private static Showtime showtime(Instant start, MovieFormat format) {
        return Showtime.builder().startTime(start).format(format).build();
    }

    private static Promotion.PromotionBuilder promo(PromotionKind kind) {
        return Promotion.builder().id(1L).code("P").title("t").kind(kind).active(true);
    }

    private void givenPromo(Promotion p) {
        when(promotions.findByCode("P")).thenReturn(Optional.of(p));
        when(promotions.findByIdForUpdate(1L)).thenReturn(Optional.of(p));
    }

    private BookingExtrasCalculator.Resolved apply(Showtime st, List<Long> prices) {
        return calculator.resolve(7L, st, prices, 0L, List.of(), "p");
    }

    @Test
    void percentOffIsRoundedToTheNearestThousandAndCapped() {
        givenPromo(promo(PromotionKind.PERCENT_OFF).percentOff(20).maxDiscount(50_000L).build());
        // 20% of 85.000 = 17.000; of 235.000 = 47.000; of 500.000 = 100.000 -> capped to 50.000
        assertThat(apply(showtime(TUESDAY, MovieFormat.TWO_D), List.of(85_000L)).discount()).isEqualTo(17_000L);
        assertThat(apply(showtime(TUESDAY, MovieFormat.TWO_D), List.of(100_000L, 135_000L)).discount()).isEqualTo(47_000L);
        assertThat(apply(showtime(TUESDAY, MovieFormat.TWO_D), List.of(250_000L, 250_000L)).discount()).isEqualTo(50_000L);
        assertThat(apply(showtime(TUESDAY, MovieFormat.TWO_D), List.of(86_250L)).discount()).isEqualTo(17_000L); // 17.250 -> 17.000
        assertThat(apply(showtime(TUESDAY, MovieFormat.TWO_D), List.of(87_500L)).discount()).isEqualTo(18_000L); // 17.500 -> 18.000
    }

    @Test
    void aFixedTicketPriceOnlyEverLowersAPrice() {
        givenPromo(promo(PromotionKind.FIXED_TICKET_PRICE).fixedPrice(50_000L).build());
        // 85.000 -> 50.000 saves 35.000; a ticket already at 40.000 saves nothing
        assertThat(apply(showtime(TUESDAY, MovieFormat.TWO_D), List.of(85_000L, 40_000L)).discount()).isEqualTo(35_000L);
    }

    @Test
    void aFixedPriceThatChangesNothingIsRefused() {
        givenPromo(promo(PromotionKind.FIXED_TICKET_PRICE).fixedPrice(50_000L).build());
        assertThatThrownBy(() -> apply(showtime(TUESDAY, MovieFormat.TWO_D), List.of(40_000L)))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("VOUCHER_INVALID");
    }

    @Test
    void theDiscountNeverExceedsTheTickets() {
        givenPromo(promo(PromotionKind.PERCENT_OFF).percentOff(100).build());
        assertThat(apply(showtime(TUESDAY, MovieFormat.TWO_D), List.of(30_000L)).discount()).isEqualTo(30_000L);
    }

    @Test
    void theCodeIsMatchedWithoutCaseOrSpaces() {
        givenPromo(promo(PromotionKind.PERCENT_OFF).percentOff(10).build());
        assertThat(calculator.resolve(7L, showtime(TUESDAY, MovieFormat.TWO_D), List.of(100_000L), 0L, List.of(), "  p ").promotion()).isNotNull();
    }

    @Test
    void noCodeMeansNoDiscountAndNoPromotionLookup() {
        BookingExtrasCalculator.Resolved r = calculator.resolve(7L, showtime(TUESDAY, MovieFormat.TWO_D), List.of(100_000L), 0L, List.of(), "  ");
        assertThat(r.discount()).isZero();
        assertThat(r.promotion()).isNull();
        verifyNoInteractions(promotions);
    }

    private void refused(Promotion p, Showtime st, String code) {
        givenPromo(p);
        assertThatThrownBy(() -> apply(st, List.of(100_000L)))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo(code);
    }

    @Test
    void unknownInactiveAndCounterOnlyCodesAreInvalid() {
        when(promotions.findByCode("P")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> apply(showtime(TUESDAY, MovieFormat.TWO_D), List.of(100_000L))).extracting("code").isEqualTo("VOUCHER_INVALID");
        refused(promo(PromotionKind.PERCENT_OFF).percentOff(10).active(false).build(), showtime(TUESDAY, MovieFormat.TWO_D), "VOUCHER_INVALID");
        refused(promo(PromotionKind.COUNTER_PERK).build(), showtime(TUESDAY, MovieFormat.TWO_D), "VOUCHER_INVALID");
    }

    @Test
    void expiredAndNotYetValidCodesAreInvalid() {
        refused(promo(PromotionKind.PERCENT_OFF).percentOff(10).validUntil(Instant.now().minusSeconds(60)).build(), showtime(TUESDAY, MovieFormat.TWO_D), "VOUCHER_INVALID");
        refused(promo(PromotionKind.PERCENT_OFF).percentOff(10).validFrom(Instant.now().plusSeconds(3600)).build(), showtime(TUESDAY, MovieFormat.TWO_D), "VOUCHER_INVALID");
    }

    @Test
    void weekdayRulesUseTheCinemasLocalDay() {
        Promotion tuesdayOnly = promo(PromotionKind.PERCENT_OFF).percentOff(10).weekdays("TUESDAY").build();
        // 20:00 UTC on Tuesday is 03:00 on Wednesday in Vietnam
        refused(tuesdayOnly, showtime(Instant.parse("2026-11-17T20:00:00Z"), MovieFormat.TWO_D), "VOUCHER_INVALID");
        givenPromo(tuesdayOnly);
        assertThat(apply(showtime(TUESDAY, MovieFormat.TWO_D), List.of(100_000L)).discount()).isPositive();
        // 18:00 UTC Monday is 01:00 Tuesday in Vietnam
        assertThat(apply(showtime(Instant.parse("2026-11-16T18:00:00Z"), MovieFormat.TWO_D), List.of(100_000L)).discount()).isPositive();
    }

    @Test
    void formatRulesAreChecked() {
        Promotion twoDOnly = promo(PromotionKind.PERCENT_OFF).percentOff(10).formats("TWO_D").build();
        refused(twoDOnly, showtime(TUESDAY, MovieFormat.IMAX), "VOUCHER_INVALID");
        givenPromo(twoDOnly);
        assertThat(apply(showtime(TUESDAY, MovieFormat.TWO_D), List.of(100_000L)).discount()).isPositive();
        assertThat(apply(showtime(TUESDAY, null), List.of(100_000L)).discount()).isPositive(); // no format = 2D
    }

    @Test
    void aFirstOrderVoucherIsRefusedToACustomerWhoAlreadyPaid() {
        when(bookings.existsByUserIdAndStatus(7L, BookingStatus.PAID)).thenReturn(true);
        refused(promo(PromotionKind.PERCENT_OFF).percentOff(10).firstOrderOnly(true).build(), showtime(TUESDAY, MovieFormat.TWO_D), "VOUCHER_NOT_ELIGIBLE");
    }

    @Test
    void aOnceOnlyVoucherIsRefusedWhileAnotherLiveBookingUsesIt() {
        when(bookingPromotions.countLiveUses(eq(7L), eq(1L), anyLong())).thenReturn(1L);
        refused(promo(PromotionKind.PERCENT_OFF).percentOff(10).oncePerUser(true).build(), showtime(TUESDAY, MovieFormat.TWO_D), "VOUCHER_NOT_ELIGIBLE");
    }

    @Test
    void aOnceOnlyVoucherTakesTheRowLockBeforeCounting() {
        Promotion p = promo(PromotionKind.PERCENT_OFF).percentOff(10).oncePerUser(true).build();
        givenPromo(p);
        apply(showtime(TUESDAY, MovieFormat.TWO_D), List.of(100_000L));
        org.mockito.InOrder order = inOrder(promotions, bookingPromotions);
        order.verify(promotions).findByIdForUpdate(1L);
        order.verify(bookingPromotions).countLiveUses(eq(7L), eq(1L), anyLong());
    }

    @Test
    void anUnrestrictedVoucherTakesNoLock() {
        givenPromo(promo(PromotionKind.PERCENT_OFF).percentOff(10).build());
        apply(showtime(TUESDAY, MovieFormat.TWO_D), List.of(100_000L));
        verify(promotions, never()).findByIdForUpdate(anyLong());
    }

    @Test
    void comboLinesAreValidatedBeforeAnyVoucherWork() {
        assertThatThrownBy(() -> calculator.resolve(7L, showtime(TUESDAY, MovieFormat.TWO_D), List.of(100_000L), 0L,
                List.of(new ComboLineRequest(1L, 0)), "p"))
                .isInstanceOf(BusinessException.class).extracting("code").isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(promotions);
    }
}
