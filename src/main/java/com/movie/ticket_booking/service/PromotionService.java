package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.PromotionRequest;
import com.movie.ticket_booking.dto.PromotionResponse;
import com.movie.ticket_booking.dto.VoucherResponse;

import java.util.List;

public interface PromotionService {
    /** Offers to show in the app: active and not past their end date. */
    List<PromotionResponse> listOffers();

    /** Every promotion, inactive and expired ones included (admin screens). */
    List<PromotionResponse> listAll();

    PromotionResponse create(PromotionRequest request);

    PromotionResponse update(Long id, PromotionRequest request);

    /** The caller's saved vouchers, newest first. */
    List<VoucherResponse> wallet(String requesterEmail);

    /** Saves a promotion to the wallet; saving it twice is fine. */
    VoucherResponse save(String requesterEmail, Long promotionId);

    /** Removes a promotion from the wallet; removing one that is not there is fine. */
    void remove(String requesterEmail, Long promotionId);
}
