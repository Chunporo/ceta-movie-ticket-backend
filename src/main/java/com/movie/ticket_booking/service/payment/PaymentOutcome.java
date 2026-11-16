package com.movie.ticket_booking.service.payment;

/** Result of applying a VNPay callback, with the RspCode/Message VNPay expects from the IPN. */
public enum PaymentOutcome {
    CONFIRMED("00", "Confirm Success"),
    /** Valid callback for a failed/cancelled payment: acknowledged, booking stays PENDING. */
    PAYMENT_FAILED("00", "Confirm Success"),
    /** Payment succeeded but seats were lost; queued for refund. */
    REFUND_QUEUED("00", "Confirm Success"),
    ALREADY_PROCESSED("02", "Order already confirmed"),
    ORDER_NOT_FOUND("01", "Order not found"),
    INVALID_AMOUNT("04", "Invalid amount"),
    INVALID_SIGNATURE("97", "Invalid signature"),
    INVALID_MERCHANT("99", "Invalid merchant");

    private final String rspCode;
    private final String message;

    PaymentOutcome(String rspCode, String message) {
        this.rspCode = rspCode;
        this.message = message;
    }

    public String rspCode() { return rspCode; }
    public String message() { return message; }
}
