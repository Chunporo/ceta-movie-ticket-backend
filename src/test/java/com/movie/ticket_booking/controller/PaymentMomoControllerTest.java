package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.config.SecurityConfig;
import com.movie.ticket_booking.service.payment.ApplyPaymentResultUseCase;
import com.movie.ticket_booking.service.payment.PaymentOutcome;
import com.movie.ticket_booking.service.payment.PaymentResult;
import com.movie.ticket_booking.service.payment.momo.MomoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentMomoController.class)
@Import(SecurityConfig.class)
class PaymentMomoControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private ApplyPaymentResultUseCase useCase;
    @MockBean private MomoService momo;
    @MockBean private com.movie.ticket_booking.service.BookingService bookingService;

    private static final String BODY = "{\"orderId\":\"5_1\",\"amount\":170000,\"resultCode\":0,\"signature\":\"x\"}";

    @Test
    void ipnIsPublicAndAnswers204WhenTheResultIsAccepted() throws Exception {
        for (PaymentOutcome ok : new PaymentOutcome[]{PaymentOutcome.CONFIRMED, PaymentOutcome.PAYMENT_FAILED,
                PaymentOutcome.REFUND_QUEUED, PaymentOutcome.ALREADY_PROCESSED}) {
            when(useCase.applyMomo(any())).thenReturn(new PaymentResult(ok, 5L));
            mockMvc.perform(post("/api/v1/payments/momo/ipn").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isNoContent());
        }
    }

    @Test
    void ipnRefusesAForgedOrMismatchedCallbackSoMomoDoesNotTreatItAsDelivered() throws Exception {
        for (PaymentOutcome bad : new PaymentOutcome[]{PaymentOutcome.INVALID_SIGNATURE, PaymentOutcome.INVALID_MERCHANT,
                PaymentOutcome.INVALID_AMOUNT, PaymentOutcome.ORDER_NOT_FOUND}) {
            when(useCase.applyMomo(any())).thenReturn(new PaymentResult(bad, null));
            mockMvc.perform(post("/api/v1/payments/momo/ipn").contentType(MediaType.APPLICATION_JSON).content(BODY))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test
    void theIpnBodyIsHandedOverAsTextSoNumbersSignTheSameWayAsMomoSignedThem() throws Exception {
        when(useCase.applyMomo(any())).thenAnswer(call -> {
            java.util.Map<String, String> params = call.getArgument(0);
            org.junit.jupiter.api.Assertions.assertEquals("170000", params.get("amount"));
            org.junit.jupiter.api.Assertions.assertEquals("0", params.get("resultCode"));
            return new PaymentResult(PaymentOutcome.CONFIRMED, 5L);
        });
        mockMvc.perform(post("/api/v1/payments/momo/ipn").contentType(MediaType.APPLICATION_JSON).content(BODY)).andExpect(status().isNoContent());
    }

    @Test
    void returnUrlIsPublicAndRedirectsToTheAppDeepLink() throws Exception {
        when(useCase.applyMomo(any())).thenReturn(new PaymentResult(PaymentOutcome.CONFIRMED, 5L));
        mockMvc.perform(get("/api/v1/payments/momo/return").param("orderId", "5_1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "ceta://payment/result?result=confirmed&bookingId=5"));
    }

    @Test
    void methodsNeedLoginAndOfferMomoOnlyWhenItIsConfigured() throws Exception {
        mockMvc.perform(get("/api/v1/payments/methods")).andExpect(status().isUnauthorized());

        when(momo.enabled()).thenReturn(false);
        mockMvc.perform(get("/api/v1/payments/methods").with(jwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("VNPAY")).andExpect(jsonPath("$[0].enabled").value(true))
                .andExpect(jsonPath("$[1].code").value("MOMO")).andExpect(jsonPath("$[1].enabled").value(false));

        when(momo.enabled()).thenReturn(true);
        mockMvc.perform(get("/api/v1/payments/methods").with(jwt())).andExpect(jsonPath("$[1].enabled").value(true));
    }
}
