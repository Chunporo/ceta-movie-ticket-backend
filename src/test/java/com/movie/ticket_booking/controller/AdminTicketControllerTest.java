package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.config.SecurityConfig;
import com.movie.ticket_booking.dto.booking.CheckInResponse;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.service.BookingService;
import com.movie.ticket_booking.service.ticket.CheckInService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminTicketController.class)
@Import(SecurityConfig.class)
class AdminTicketControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private CheckInService checkIn;
    @MockBean private BookingService bookingService;

    private static final String URL = "/api/v1/admin/tickets/check-in";

    @Test
    void staffCheckInReturnsWhatTheDoorNeeds() throws Exception {
        when(checkIn.checkIn(eq("staff@b.c"), any())).thenReturn(new CheckInResponse(9L, "TCK-1", "F7", "Movie", "Room 1",
                Instant.parse("2030-01-01T12:00:00Z"), Instant.parse("2030-01-01T11:50:00Z")));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"qr\":\"CETA1.9.1.sig\"}")
                        .with(jwt().jwt(j -> j.subject("staff@b.c")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seatLabel").value("F7"))
                .andExpect(jsonPath("$.checkedInAt").value("2030-01-01T11:50:00Z"));
    }

    @Test
    void secondScanKeepsItsCodeAndDetails() throws Exception {
        when(checkIn.checkIn(any(), any())).thenThrow(new BusinessException(HttpStatus.CONFLICT, "ALREADY_CHECKED_IN",
                "used", List.of(Map.of("checkedInAt", "2030-01-01T11:50:00Z"))));

        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"TCK-1\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALREADY_CHECKED_IN"))
                .andExpect(jsonPath("$.details[0].checkedInAt").value("2030-01-01T11:50:00Z"));
    }

    @Test
    void customersAndAnonymousCannotCheckTicketsIn() throws Exception {
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"TCK-1\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"TCK-1\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(checkIn);
    }
}
