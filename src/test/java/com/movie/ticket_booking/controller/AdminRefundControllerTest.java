package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.config.SecurityConfig;
import com.movie.ticket_booking.dto.booking.RefundResponse;
import com.movie.ticket_booking.entity.enums.RefundStatus;
import com.movie.ticket_booking.service.BookingService;
import com.movie.ticket_booking.service.RefundService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminRefundController.class)
@Import(SecurityConfig.class)
class AdminRefundControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private RefundService refunds;
    @MockBean private BookingService bookingService;

    private static final RefundResponse ROW = new RefundResponse(3L, 9L, "LATE_PAYMENT", 100000L, "PENDING", null, Instant.parse("2030-01-01T00:00:00Z"), null);

    @Test
    void ordinaryUserGets403OnEveryAdminRefundEndpointAndNothingRuns() throws Exception {
        var user = jwt().authorities(new SimpleGrantedAuthority("ROLE_USER"));

        mockMvc.perform(get("/api/v1/admin/refunds").with(user)).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/admin/refunds/3/done").with(user).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/admin/refunds/3/failed").with(user).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(refunds);
    }

    @Test
    void anonymousIs401() throws Exception {
        mockMvc.perform(get("/api/v1/admin/refunds")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminListsPendingAndResolves() throws Exception {
        var admin = jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
        when(refunds.list(RefundStatus.PENDING)).thenReturn(List.of(ROW));
        when(refunds.markDone(eq(3L), eq("paid back"))).thenReturn(new RefundResponse(3L, 9L, "LATE_PAYMENT", 100000L, "DONE", "paid back", ROW.createdAt(), Instant.parse("2030-01-02T00:00:00Z")));

        mockMvc.perform(get("/api/v1/admin/refunds").param("status", "PENDING").with(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].bookingId").value(9))
                .andExpect(jsonPath("$[0].status").value("PENDING"));
        mockMvc.perform(put("/api/v1/admin/refunds/3/done").with(admin).contentType(MediaType.APPLICATION_JSON).content("{\"note\":\"paid back\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));
    }

    @Test
    void unknownStatusFilterIs400NotAServerError() throws Exception {
        mockMvc.perform(get("/api/v1/admin/refunds").param("status", "BOGUS")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
                .andExpect(status().isBadRequest());
    }
}
