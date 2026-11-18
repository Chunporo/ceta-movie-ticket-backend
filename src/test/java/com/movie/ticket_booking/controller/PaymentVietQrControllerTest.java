package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.config.SecurityConfig;
import com.movie.ticket_booking.service.payment.ApplyPaymentResultUseCase;
import com.movie.ticket_booking.service.payment.PaymentOutcome;
import com.movie.ticket_booking.service.payment.PaymentResult;
import com.movie.ticket_booking.service.payment.vietqr.VietQrService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentMomoController.class)
@Import(SecurityConfig.class)
class PaymentVietQrControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private ApplyPaymentResultUseCase useCase;
    @MockBean private com.movie.ticket_booking.service.payment.momo.MomoService momo;
    @MockBean private VietQrService vietQr;
    @MockBean private com.movie.ticket_booking.service.BookingService bookingService;

    private static final String BODY = "{\"transferType\":\"in\",\"transferAmount\":170000,\"content\":\"CETA5A1\"}";

    @Test
    void aMissingOrWrongKeyIsRefusedBeforeAnythingIsSettled() throws Exception {
        when(vietQr.verifyKey(any())).thenReturn(false);
        mockMvc.perform(post("/api/v1/payments/vietqr/webhook").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(useCase);
    }

    @Test
    void anAcceptedTransferIsAcknowledgedAndAWrongAmountIsRetried() throws Exception {
        when(vietQr.verifyKey("Apikey k")).thenReturn(true);
        when(useCase.applyVietQr(any())).thenReturn(new PaymentResult(PaymentOutcome.CONFIRMED, 5L));
        mockMvc.perform(post("/api/v1/payments/vietqr/webhook").header("Authorization", "Apikey k").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        when(useCase.applyVietQr(any())).thenReturn(new PaymentResult(PaymentOutcome.INVALID_AMOUNT, 5L));
        mockMvc.perform(post("/api/v1/payments/vietqr/webhook").header("Authorization", "Apikey k").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
    }
}
