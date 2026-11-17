package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.config.SecurityConfig;
import com.movie.ticket_booking.dto.PromotionRequest;
import com.movie.ticket_booking.dto.PromotionResponse;
import com.movie.ticket_booking.dto.VoucherResponse;
import com.movie.ticket_booking.entity.enums.PromotionKind;
import com.movie.ticket_booking.service.PromotionService;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({PromotionController.class, VoucherController.class})
@Import(SecurityConfig.class)
class PromotionControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private PromotionService promotionService;

    private static PromotionResponse ceta20() {
        return new PromotionResponse(1L, "CETA20", "Thành viên mới -20%", "New members -20%", "d", "d", null, PromotionKind.PERCENT_OFF,
                20, 50_000L, null, List.of(), List.of(), true, true, null, Instant.parse("2027-01-31T16:59:59Z"), true, 1);
    }

    @Test
    void anyoneCanSeeTheOffers() throws Exception {
        when(promotionService.listOffers()).thenReturn(List.of(ceta20()));
        mockMvc.perform(get("/api/v1/promotions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("CETA20"))
                .andExpect(jsonPath("$[0].percentOff").value(20))
                .andExpect(jsonPath("$[0].maxDiscount").value(50000));
    }

    @Test
    void theWalletNeedsALoginAndIsReadFromTheCallersToken() throws Exception {
        mockMvc.perform(get("/api/v1/vouchers")).andExpect(status().isUnauthorized());
        when(promotionService.wallet("a@b.vn")).thenReturn(List.of(new VoucherResponse(ceta20(), Instant.parse("2026-11-01T00:00:00Z"))));
        mockMvc.perform(get("/api/v1/vouchers").with(jwt().jwt(j -> j.subject("a@b.vn"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].promotion.code").value("CETA20"));
    }

    @Test
    void savingAndRemovingUseTheCallersEmail() throws Exception {
        when(promotionService.save("a@b.vn", 1L)).thenReturn(new VoucherResponse(ceta20(), Instant.now()));
        mockMvc.perform(put("/api/v1/vouchers/1").with(jwt().jwt(j -> j.subject("a@b.vn")))).andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/vouchers/1").with(jwt().jwt(j -> j.subject("a@b.vn")))).andExpect(status().isNoContent());
        verify(promotionService).remove("a@b.vn", 1L);
    }

    @Test
    void onlyAdminsCreateOffers() throws Exception {
        String body = """
                {"code":"NEW","title":"New","kind":"PERCENT_OFF","percentOff":10}""";
        when(promotionService.create(any(PromotionRequest.class))).thenReturn(ceta20());
        mockMvc.perform(post("/api/v1/promotions").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        verifyNoInteractions(promotionService);
        mockMvc.perform(post("/api/v1/promotions").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated());
    }

    @Test
    void aPercentOutOfRangeIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/promotions").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"code":"NEW","title":"New","kind":"PERCENT_OFF","percentOff":150}""")).andExpect(status().isBadRequest());
    }
}
