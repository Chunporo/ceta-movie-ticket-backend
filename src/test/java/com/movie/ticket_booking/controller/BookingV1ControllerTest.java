package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.config.SecurityConfig;
import com.movie.ticket_booking.dto.booking.CreateBookingResponse;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.exception.SeatConflictException;
import com.movie.ticket_booking.service.BookingCreationService;
import com.movie.ticket_booking.service.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BookingV1Controller.class)
@Import(SecurityConfig.class)
class BookingV1ControllerTest {
    private static final String BODY = "{\"showtimeId\":1,\"seatIds\":[7]}";

    @Autowired private MockMvc mockMvc;
    @MockBean private BookingCreationService creation;
    @MockBean private com.movie.ticket_booking.service.BookingExtrasService extrasService;
    @MockBean private BookingService bookingService;
    @MockBean private com.movie.ticket_booking.service.BookingLifecycleService lifecycle;
    @MockBean private com.movie.ticket_booking.service.PaidBookingCancellationService paidCancellation;

    @Test
    void createReturns201WithContract() throws Exception {
        when(creation.create(eq("a@b.c"), any())).thenReturn(new CreateBookingResponse(5L, Instant.parse("2030-01-01T00:10:00Z"), 120000L,
                List.of(new CreateBookingResponse.Seat(7L, "F7", 120000L))));

        mockMvc.perform(post("/api/v1/bookings").with(jwt().jwt(j -> j.subject("a@b.c")))
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.bookingId").value(5))
                .andExpect(jsonPath("$.totalAmount").value(120000))
                .andExpect(jsonPath("$.holdExpiresAt").value("2030-01-01T00:10:00Z"))
                .andExpect(jsonPath("$.seats[0].seatId").value(7));
    }

    @Test
    void listIsPagedForTheCaller() throws Exception {
        when(bookingService.getUserBookings("a@b.c", 1, 5)).thenReturn(new com.movie.ticket_booking.dto.booking.PageResponse<>(List.of(), 1, 5, 6L, 2));

        mockMvc.perform(get("/api/v1/bookings").param("page", "1").param("size", "5").with(jwt().jwt(j -> j.subject("a@b.c"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalItems").value(6))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    @Test
    void cancelPaidReturnsTheRefundedBooking() throws Exception {
        when(paidCancellation.cancel("a@b.c", 5L)).thenReturn(new com.movie.ticket_booking.dto.booking.CancelPaidBookingResponse(5L, "REFUNDED", 120000L));

        mockMvc.perform(post("/api/v1/bookings/5/cancel").with(jwt().jwt(j -> j.subject("a@b.c"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundAmount").value(120000));
    }

    @Test
    void cancelPaidLockedWindowKeepsItsCode() throws Exception {
        when(paidCancellation.cancel(any(), any())).thenThrow(new com.movie.ticket_booking.exception.BusinessException(
                org.springframework.http.HttpStatus.CONFLICT, "BOOKING_NOT_CANCELLABLE", "too late"));

        mockMvc.perform(post("/api/v1/bookings/5/cancel").with(jwt().jwt(j -> j.subject("a@b.c"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BOOKING_NOT_CANCELLABLE"));
    }

    @Test
    void cancelPaidNeedsLogin() throws Exception {
        mockMvc.perform(post("/api/v1/bookings/5/cancel")).andExpect(status().isUnauthorized());
        verifyNoInteractions(paidCancellation);
    }

    @Test
    void seatConflictIs409WithDetails() throws Exception {
        when(creation.create(any(), any())).thenThrow(new SeatConflictException(List.of(7L)));

        mockMvc.perform(post("/api/v1/bookings").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEAT_CONFLICT"))
                .andExpect(jsonPath("$.details[0].seatId").value(7));
    }

    @Test
    void businessErrorsKeepTheirCodeAndStatus() throws Exception {
        when(creation.create(any(), any())).thenThrow(new BusinessException(HttpStatus.BAD_REQUEST, "MAX_SEATS_EXCEEDED", "too many"));

        mockMvc.perform(post("/api/v1/bookings").with(jwt()).contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MAX_SEATS_EXCEEDED"));
    }

    @Test
    void anonymousIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/bookings").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deleteReturns204AndForbiddenKeepsItsCode() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/bookings/5").with(jwt()))
                .andExpect(status().isNoContent());

        org.mockito.Mockito.doThrow(new BusinessException(HttpStatus.FORBIDDEN, "FORBIDDEN", "no"))
                .when(lifecycle).cancel(any(), eq(6L));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/api/v1/bookings/6").with(jwt()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
