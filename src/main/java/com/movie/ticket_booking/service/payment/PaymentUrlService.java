package com.movie.ticket_booking.service.payment;

import com.movie.ticket_booking.dto.booking.CreatePaymentUrlResponse;

public interface PaymentUrlService {
    /** Starts a payment attempt for the requester's PENDING booking and returns the signed VNPay URL. */
    default CreatePaymentUrlResponse create(String requesterEmail, Long bookingId, String clientIp) {
        return create(requesterEmail, bookingId, clientIp, null);
    }

    /** {@code provider}: VNPAY (default when null or blank) or MOMO. */
    CreatePaymentUrlResponse create(String requesterEmail, Long bookingId, String clientIp, String provider);
}
