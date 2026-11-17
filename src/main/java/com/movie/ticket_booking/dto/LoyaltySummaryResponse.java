package com.movie.ticket_booking.dto;

/**
 * The account page: CETA points, tier and the counters. {@code pointsToNextTier} is 0 and {@code nextTier} null at the top.
 * One redeem block is {@code redeemBlockPoints} points worth {@code redeemBlockVnd} d.
 */
public record LoyaltySummaryResponse(long balance, long lifetimeEarned, String tier, String nextTier, long pointsToNextTier,
                                     int redeemBlockPoints, long redeemBlockVnd, Stats stats) {
    public record Stats(long ticketsWatched, long vouchers) {}
}
