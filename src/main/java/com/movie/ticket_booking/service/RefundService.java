package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.booking.RefundResponse;
import com.movie.ticket_booking.entity.enums.RefundReason;
import com.movie.ticket_booking.entity.enums.RefundStatus;

import java.util.List;

/** Manual refunds (D-07, D-13): rare late/double payments, reconciled by an admin against VNPay. */
public interface RefundService {
    /** Idempotent: a booking is never queued twice. Joins the caller's transaction. */
    void enqueue(Long bookingId, RefundReason reason, Long amount, String note);

    List<RefundResponse> list(RefundStatus status);

    RefundResponse markDone(Long id, String note);

    RefundResponse markFailed(Long id, String note);
}
