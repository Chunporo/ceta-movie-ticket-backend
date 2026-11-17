package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.ComboLineRequest;
import com.movie.ticket_booking.entity.*;
import com.movie.ticket_booking.entity.enums.BookingStatus;
import com.movie.ticket_booking.entity.enums.MovieFormat;
import com.movie.ticket_booking.entity.enums.PromotionKind;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.util.*;

/**
 * Works out what a booking adds on top of its seats: snacks and a voucher discount. Used when a booking is created
 * and when the customer changes snacks or the voucher before paying. Seat prices come in as the frozen snapshot,
 * never recomputed.
 */
@Component
public class BookingExtrasCalculator {
    private final ComboRepository combos;
    private final PromotionRepository promotions;
    private final BookingPromotionRepository bookingPromotions;
    private final BookingComboRepository bookingCombos;
    private final BookingRepository bookings;
    private final int maxComboQuantity;
    private final int redeemBlockPoints;
    private final long redeemBlockVnd;
    private final ZoneId zone;

    public BookingExtrasCalculator(ComboRepository combos, PromotionRepository promotions,
                                   BookingPromotionRepository bookingPromotions, BookingComboRepository bookingCombos,
                                   BookingRepository bookings,
                                   @Value("${app.booking.max-quantity-per-combo:10}") int maxComboQuantity,
                                   @Value("${app.loyalty.redeem-block-points:2000}") int redeemBlockPoints,
                                   @Value("${app.loyalty.redeem-block-vnd:20000}") long redeemBlockVnd,
                                   @Value("${app.timezone:Asia/Ho_Chi_Minh}") String timezone) {
        this.combos = combos;
        this.promotions = promotions;
        this.bookingPromotions = bookingPromotions;
        this.bookingCombos = bookingCombos;
        this.bookings = bookings;
        this.maxComboQuantity = maxComboQuantity;
        this.redeemBlockPoints = redeemBlockPoints;
        this.redeemBlockVnd = redeemBlockVnd;
        this.zone = ZoneId.of(timezone);
    }

    /** Points are spent in one block (default 2.000 points = 20.000d). */
    public int redeemBlockPoints() {
        return redeemBlockPoints;
    }

    public long redeemBlockVnd() {
        return redeemBlockVnd;
    }

    public record ComboPick(Combo combo, int quantity) {
        long lineTotal() {
            return Math.multiplyExact(combo.getPrice(), (long) quantity);
        }
    }

    /** {@code promotion} is null when no voucher is used; {@code discount} is then 0. */
    public record Resolved(List<ComboPick> combos, long comboTotal, Promotion promotion, long discount) {
        public static Resolved none() {
            return new Resolved(List.of(), 0, null, 0);
        }
    }

    /**
     * @param excludeBookingId the booking being edited (0 for a booking not created yet), so it does not count against itself
     */
    public Resolved resolve(Long userId, Showtime showtime, List<Long> seatPrices, long excludeBookingId,
                            List<ComboLineRequest> comboLines, String voucherCode) {
        List<ComboPick> picks = resolveCombos(comboLines);
        long comboTotal = picks.stream().mapToLong(ComboPick::lineTotal).sum();
        if (voucherCode == null || voucherCode.isBlank()) {
            return new Resolved(picks, comboTotal, null, 0);
        }
        Promotion promotion = applicablePromotion(userId, showtime, voucherCode, excludeBookingId);
        long discount = discountFor(promotion, seatPrices);
        if (discount <= 0) {
            throw invalid("The voucher gives no discount on these seats");
        }
        return new Resolved(picks, comboTotal, promotion, discount);
    }

    /** Writes the combo lines and the voucher use of a booking (the caller has cleared any earlier ones). */
    public List<BookingCombo> persist(Long bookingId, Long userId, Resolved resolved) {
        List<BookingCombo> lines = resolved.combos().stream().map(pick -> BookingCombo.builder()
                .id(new BookingComboId(bookingId, pick.combo().getId())).name(pick.combo().getName())
                .quantity(pick.quantity()).unitPrice(pick.combo().getPrice()).build()).toList();
        bookingCombos.saveAll(lines);
        bookingCombos.flush();
        if (resolved.promotion() != null) {
            bookingPromotions.saveAndFlush(BookingPromotion.builder().bookingId(bookingId).promotionId(resolved.promotion().getId())
                    .userId(userId).code(resolved.promotion().getCode()).discount(resolved.discount()).createdAt(Instant.now()).build());
        }
        return lines;
    }

    private List<ComboPick> resolveCombos(List<ComboLineRequest> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        Set<Long> seen = new HashSet<>();
        for (ComboLineRequest line : lines) {
            if (line == null || line.comboId() == null || line.quantity() == null || line.quantity() < 1
                    || line.quantity() > maxComboQuantity || !seen.add(line.comboId())) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                        "Each combo needs a distinct comboId and a quantity from 1 to " + maxComboQuantity);
            }
        }
        Map<Long, Combo> byId = new HashMap<>();
        combos.findAllById(seen).forEach(c -> byId.put(c.getId(), c));
        List<ComboPick> picks = new ArrayList<>();
        for (ComboLineRequest line : lines) {
            Combo combo = byId.get(line.comboId());
            if (combo == null || !combo.isActive()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "COMBO_UNAVAILABLE", "Combo " + line.comboId() + " is not available");
            }
            picks.add(new ComboPick(combo, line.quantity()));
        }
        return picks;
    }

    private Promotion applicablePromotion(Long userId, Showtime showtime, String voucherCode, long excludeBookingId) {
        Promotion promotion = promotions.findByCode(voucherCode.trim().toUpperCase(Locale.ROOT))
                .filter(Promotion::isActive)
                .filter(p -> p.getKind() != PromotionKind.COUNTER_PERK)
                .orElseThrow(() -> invalid("Unknown voucher"));
        Instant now = Instant.now();
        if ((promotion.getValidFrom() != null && now.isBefore(promotion.getValidFrom()))
                || (promotion.getValidUntil() != null && !now.isBefore(promotion.getValidUntil()))) {
            throw invalid("The voucher is not valid today");
        }
        if (promotion.getWeekdays() != null && !promotion.getWeekdays().isBlank()) {
            DayOfWeek day = showtime.getStartTime().atZone(zone).getDayOfWeek();
            if (!csv(promotion.getWeekdays()).contains(day.name())) {
                throw invalid("The voucher does not apply to this day");
            }
        }
        if (promotion.getFormats() != null && !promotion.getFormats().isBlank()) {
            MovieFormat format = showtime.getFormat() == null ? MovieFormat.TWO_D : showtime.getFormat();
            if (!csv(promotion.getFormats()).contains(format.name())) {
                throw invalid("The voucher does not apply to this format");
            }
        }
        if (promotion.isFirstOrderOnly() || promotion.isOncePerUser()) {
            // Two bookings of one customer racing for the same once-only voucher queue up here.
            promotions.findByIdForUpdate(promotion.getId());
            if (promotion.isFirstOrderOnly() && bookings.existsByUserIdAndStatus(userId, BookingStatus.PAID)) {
                throw notEligible("The voucher is for a first order only");
            }
            if (promotion.isOncePerUser() && bookingPromotions.countLiveUses(userId, promotion.getId(), excludeBookingId) > 0) {
                throw notEligible("The voucher has already been used");
            }
        }
        return promotion;
    }

    /** Discount on the tickets only (snacks are never discounted), whole VND, never more than the tickets cost. */
    static long discountFor(Promotion promotion, List<Long> seatPrices) {
        long seats = seatPrices.stream().mapToLong(Long::longValue).sum();
        long discount = switch (promotion.getKind()) {
            case PERCENT_OFF -> {
                long raw = seats * promotion.getPercentOff() / 100;
                long rounded = (raw + 500) / 1000 * 1000; // to the nearest 1.000d, like the app shows it
                yield promotion.getMaxDiscount() == null ? rounded : Math.min(rounded, promotion.getMaxDiscount());
            }
            case FIXED_TICKET_PRICE -> seatPrices.stream().mapToLong(p -> Math.max(0, p - promotion.getFixedPrice())).sum();
            case COUNTER_PERK -> 0;
        };
        return Math.min(discount, seats);
    }

    private static Set<String> csv(String value) {
        return new HashSet<>(Arrays.asList(value.split("\\s*,\\s*")));
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(HttpStatus.BAD_REQUEST, "VOUCHER_INVALID", message);
    }

    private static BusinessException notEligible(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "VOUCHER_NOT_ELIGIBLE", message);
    }
}
