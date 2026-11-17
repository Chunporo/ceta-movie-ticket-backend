package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.LoyaltyAdjustRequest;
import com.movie.ticket_booking.dto.LoyaltySummaryResponse;

public interface LoyaltyService {
    LoyaltySummaryResponse summary(String requesterEmail);

    /** Admin correction; returns the customer's new balance. Refuses to take a balance below zero. */
    LoyaltySummaryResponse adjust(LoyaltyAdjustRequest request);
}
