package com.movie.ticket_booking.service.payment;

import java.util.Map;

/** Shared by the VNPay and MoMo IPNs and return URLs (D-10). */
public interface ApplyPaymentResultUseCase {
    PaymentResult apply(Map<String, String> params);

    /** A MoMo IPN body or redirect query (all values as text); same settlement rules as VNPay. */
    PaymentResult applyMomo(Map<String, String> params);
}
