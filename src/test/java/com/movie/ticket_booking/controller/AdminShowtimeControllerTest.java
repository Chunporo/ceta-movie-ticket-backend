package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.config.SecurityConfig;
import com.movie.ticket_booking.dto.booking.CancelShowtimeResponse;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.service.BookingService;
import com.movie.ticket_booking.service.ShowtimeCancellationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminShowtimeController.class)
@Import(SecurityConfig.class)
class AdminShowtimeControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private ShowtimeCancellationService cancellation;
    @MockBean private BookingService bookingService;

    @Test
    void adminCancelsWithAReason() throws Exception {
        when(cancellation.cancel(eq(5L), eq("Projector broke"), any())).thenReturn(new CancelShowtimeResponse(5L, "CANCELLED", 3, 1));

        mockMvc.perform(post("/api/v1/admin/showtimes/5/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Projector broke\"}")
                        .with(jwt().jwt(j -> j.subject("admin@b.c")).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refunded").value(3))
                .andExpect(jsonPath("$.released").value(1));
        verify(cancellation).cancel(5L, "Projector broke", "admin@b.c");
    }

    @Test
    void aMissingOrBlankReasonIs400AndNothingRuns() throws Exception {
        for (String body : new String[]{"{}", "{\"reason\":\"  \"}"}) {
            mockMvc.perform(post("/api/v1/admin/showtimes/5/cancel").contentType(MediaType.APPLICATION_JSON).content(body)
                            .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(cancellation);
    }

    @Test
    void customerGets403AndAnonymousGets401() throws Exception {
        mockMvc.perform(post("/api/v1/admin/showtimes/5/cancel").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/showtimes/5/cancel").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(cancellation);
    }

    @Test
    void serviceErrorsKeepTheirStatus() throws Exception {
        when(cancellation.cancel(any(), any(), any())).thenThrow(new BusinessException(HttpStatus.NOT_FOUND, "SHOWTIME_NOT_FOUND", "nope"));
        mockMvc.perform(post("/api/v1/admin/showtimes/9/cancel").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHOWTIME_NOT_FOUND"));
    }
}
