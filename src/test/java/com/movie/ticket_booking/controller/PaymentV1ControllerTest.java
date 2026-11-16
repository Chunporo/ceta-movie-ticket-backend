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
}
