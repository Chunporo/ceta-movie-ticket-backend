package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.config.SecurityConfig;
import com.movie.ticket_booking.service.BookingService;
import com.movie.ticket_booking.service.payment.ApplyPaymentResultUseCase;
import com.movie.ticket_booking.service.payment.PaymentOutcome;
import com.movie.ticket_booking.service.payment.PaymentResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentV1Controller.class)
@Import(SecurityConfig.class)
class PaymentV1ControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private ApplyPaymentResultUseCase useCase;
    @MockBean private com.movie.ticket_booking.service.payment.PaymentUrlService paymentUrls;
    @MockBean private BookingService bookingService;

    @Test
    void ipnIsPublicAcceptsGetAndPostAndAnswersInVnpayFormat() throws Exception {
        when(useCase.apply(any())).thenReturn(new PaymentResult(PaymentOutcome.CONFIRMED, 5L));

        mockMvc.perform(get("/api/v1/payments/vnpay/ipn").param("vnp_TxnRef", "5_1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RspCode").value("00"))
                .andExpect(jsonPath("$.Message").value("Confirm Success"));
        mockMvc.perform(post("/api/v1/payments/vnpay/ipn").param("vnp_TxnRef", "5_1"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectedIpnStillReturns200WithItsRspCode() throws Exception {
        when(useCase.apply(any())).thenReturn(new PaymentResult(PaymentOutcome.INVALID_SIGNATURE, null));

        mockMvc.perform(get("/api/v1/payments/vnpay/ipn"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.RspCode").value("97"));
    }

    @Test
    void returnUrlIsPublicAndRedirectsToTheAppDeepLink() throws Exception {
        when(useCase.apply(any())).thenReturn(new PaymentResult(PaymentOutcome.CONFIRMED, 5L));

        mockMvc.perform(get("/api/v1/payments/vnpay/return").param("vnp_TxnRef", "5_1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "ceta://payment/result?result=confirmed&bookingId=5"));
    }

    @Test
    void createUrlNeedsLoginAndReturnsPayUrlAndTxnRef() throws Exception {
        when(paymentUrls.create(org.mockito.ArgumentMatchers.eq("a@b.c"), org.mockito.ArgumentMatchers.eq(5L), any()))
                .thenReturn(new com.movie.ticket_booking.dto.booking.CreatePaymentUrlResponse("https://pay.example/x?y=1", "5_1"));

        mockMvc.perform(post("/api/v1/payments/vnpay/create-url").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":5}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/payments/vnpay/create-url").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":5}")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject("a@b.c"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payUrl").value("https://pay.example/x?y=1"))
                .andExpect(jsonPath("$.txnRef").value("5_1"));
    }
}
