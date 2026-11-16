package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.config.SecurityConfig;
import com.movie.ticket_booking.dto.booking.PageResponse;
import com.movie.ticket_booking.dto.booking.TicketResponse;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.service.BookingService;
import com.movie.ticket_booking.service.ticket.TicketQueryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TicketV1Controller.class)
@Import(SecurityConfig.class)
class TicketV1ControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private TicketQueryService tickets;
    @MockBean private BookingService bookingService;

    private static TicketResponse sample() {
        return new TicketResponse(9L, 5L, "TCK-abc", 7L, "F7", 120000L,
                new TicketResponse.Showtime(3L, Instant.parse("2030-01-01T12:00:00Z"), Instant.parse("2030-01-01T14:00:00Z"), "Movie", "Room 1"),
                "CETA1.9.1893506400.sig");
    }

    @Test
    void myTicketsPassesThePagingAndReturnsTheEnvelope() throws Exception {
        when(tickets.myTickets("a@b.c", 1, 5)).thenReturn(new PageResponse<>(List.of(sample()), 1, 5, 6, 2));

        mockMvc.perform(get("/api/v1/tickets/my").param("page", "1").param("size", "5").with(jwt().jwt(j -> j.subject("a@b.c"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalItems").value(6))
                .andExpect(jsonPath("$.items[0].seatLabel").value("F7"))
                .andExpect(jsonPath("$.items[0].qrPayload").value("CETA1.9.1893506400.sig"))
                .andExpect(jsonPath("$.items[0].showtime.startTime").value("2030-01-01T12:00:00Z"))
                .andExpect(jsonPath("$.items[0].showtime.movieTitle").value("Movie"));
    }

    @Test
    void defaultsAndAuthentication() throws Exception {
        when(tickets.myTickets("a@b.c", 0, 20)).thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get("/api/v1/tickets/my")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/tickets/my").with(jwt().jwt(j -> j.subject("a@b.c"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void oneTicketOrA404() throws Exception {
        when(tickets.get("a@b.c", 9L)).thenReturn(sample());
        when(tickets.get("a@b.c", 10L)).thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND", "Ticket not found"));
        var user = jwt().jwt(j -> j.subject("a@b.c"));

        mockMvc.perform(get("/api/v1/tickets/9").with(user)).andExpect(status().isOk())
                .andExpect(jsonPath("$.ticketId").value(9)).andExpect(jsonPath("$.price").value(120000));
        mockMvc.perform(get("/api/v1/tickets/10").with(user)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TICKET_NOT_FOUND"));
    }
}
