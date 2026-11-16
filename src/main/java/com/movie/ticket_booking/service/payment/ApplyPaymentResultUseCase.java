package com.movie.ticket_booking.service.payment;

import java.util.Map;

/** Shared by the VNPay IPN and the return URL (D-10). */
public interface ApplyPaymentResultUseCase {
    PaymentResult apply(Map<String, String> params);
}
