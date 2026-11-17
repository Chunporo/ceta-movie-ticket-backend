package com.movie.ticket_booking.service.payment.momo;

import java.util.Map;

/** The one place that talks to MoMo over HTTP. A seam so tests and the local simulator never reach the real service. */
public interface MomoGateway {
    /** What MoMo answers to a create request: {@code resultCode} 0 means a pay URL was issued. */
    record CreateResult(int resultCode, String message, String payUrl) {
    }

    CreateResult create(String endpoint, Map<String, Object> body);
}
