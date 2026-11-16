package com.movie.ticket_booking.service.ticket;

import com.movie.ticket_booking.dto.booking.PageResponse;
import com.movie.ticket_booking.dto.booking.TicketResponse;

public interface TicketQueryService {
    PageResponse<TicketResponse> myTickets(String requesterEmail, int page, int size);

    /** 404 for an unknown ticket and for someone else's ticket alike, so ids cannot be probed. */
    TicketResponse get(String requesterEmail, Long ticketId);
}
