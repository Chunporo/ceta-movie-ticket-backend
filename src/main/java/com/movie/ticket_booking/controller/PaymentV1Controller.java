package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.dto.booking.CreatePaymentUrlRequest;
import com.movie.ticket_booking.dto.booking.CreatePaymentUrlResponse;
import com.movie.ticket_booking.service.VNPayService;
import com.movie.ticket_booking.service.payment.ApplyPaymentResultUseCase;
import com.movie.ticket_booking.service.payment.PaymentUrlService;
import com.movie.ticket_booking.service.payment.PaymentOutcome;
import com.movie.ticket_booking.service.payment.PaymentResult;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/payments/vnpay")
@RequiredArgsConstructor
@Slf4j
public class PaymentV1Controller {
    private final ApplyPaymentResultUseCase applyPayment;
    private final PaymentUrlService paymentUrls;

    @Value("${app.payment.return-claim:true}")
    private boolean returnClaim;
    @Value("${app.payment.return-deep-link:ceta://payment/result}")
    private String deepLink;

    @PostMapping("/create-url")
    @PreAuthorize("isAuthenticated()")
    public CreatePaymentUrlResponse createUrl(@AuthenticationPrincipal Jwt jwt, @RequestBody CreatePaymentUrlRequest request,
                                              HttpServletRequest http) {
        return paymentUrls.create(jwt == null ? null : jwt.getSubject(),
                request == null ? null : request.bookingId(), VNPayService.getClientIp(http));
    }

    /** VNPay calls the IPN with GET; POST is accepted too. Always HTTP 200 with RspCode/Message. */
    @RequestMapping(value = "/ipn", method = {RequestMethod.GET, RequestMethod.POST})
    @PreAuthorize("permitAll()")
    public Map<String, String> ipn(HttpServletRequest request) {
        PaymentOutcome outcome = applyPayment.apply(params(request)).outcome();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("RspCode", outcome.rspCode());
        body.put("Message", outcome.message());
        return body;
    }

    @GetMapping("/return")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> returnUrl(HttpServletRequest request) {
        Map<String, String> params = params(request);
        PaymentResult result = returnClaim ? applyPayment.apply(params) : null;
        String target = deepLink + "?result=" + (result == null ? "pending" : result.outcome().name().toLowerCase())
                + (result != null && result.bookingId() != null ? "&bookingId=" + result.bookingId() : "");
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, URI.create(target).toString()).build();
    }

    private static Map<String, String> params(HttpServletRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
        return params;
    }
}
