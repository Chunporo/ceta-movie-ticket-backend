package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.config.SecurityConfig;
import com.movie.ticket_booking.dto.LoyaltyAdjustRequest;
import com.movie.ticket_booking.dto.LoyaltySummaryResponse;
import com.movie.ticket_booking.service.LoyaltyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(LoyaltyController.class)
@Import(SecurityConfig.class)
class LoyaltyControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private LoyaltyService loyaltyService;

    private static LoyaltySummaryResponse summary() {
        return new LoyaltySummaryResponse(2450, 2450, "MEMBER", "VIP", 2550, 2000, 20_000L, new LoyaltySummaryResponse.Stats(12, 3, 5));
    }

    @Test
    void theAccountPageReadsPointsTierAndCountersForTheCaller() throws Exception {
        mockMvc.perform(get("/api/v1/me/loyalty")).andExpect(status().isUnauthorized());

        when(loyaltyService.summary("a@b.vn")).thenReturn(summary());
        mockMvc.perform(get("/api/v1/me/loyalty").with(jwt().jwt(j -> j.subject("a@b.vn"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(2450))
                .andExpect(jsonPath("$.pointsToNextTier").value(2550))
                .andExpect(jsonPath("$.nextTier").value("VIP"))
                .andExpect(jsonPath("$.redeemBlockVnd").value(20000))
                .andExpect(jsonPath("$.stats.ticketsWatched").value(12))
                .andExpect(jsonPath("$.stats.vouchers").value(3))
                .andExpect(jsonPath("$.stats.reviews").value(5));
    }

    @Test
    void onlyAnAdminAdjustsPointsAndNeedsANote() throws Exception {
        String body = "{\"email\":\"a@b.vn\",\"points\":500,\"note\":\"goodwill\"}";
        mockMvc.perform(post("/api/v1/admin/loyalty/adjust").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(loyaltyService);

        when(loyaltyService.adjust(any(LoyaltyAdjustRequest.class))).thenReturn(summary());
        mockMvc.perform(post("/api/v1/admin/loyalty/adjust").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/admin/loyalty/adjust").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"a@b.vn\",\"points\":500,\"note\":\"\"}")).andExpect(status().isBadRequest());
    }
}
