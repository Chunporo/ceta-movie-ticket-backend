package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.service.payment.ApplyPaymentResultUseCase;
import com.movie.ticket_booking.service.payment.PaymentOutcome;
import com.movie.ticket_booking.service.payment.PaymentResult;
import com.movie.ticket_booking.service.payment.momo.MomoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** MoMo's callbacks, and which payment methods the app may offer. VNPay's own endpoints stay in {@link PaymentV1Controller}. */
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Slf4j
public class PaymentMomoController {
    private final ApplyPaymentResultUseCase applyPayment;
    private final ObjectProvider<MomoService> momo;
    private final ObjectProvider<com.movie.ticket_booking.service.payment.vietqr.VietQrService> vietQr;

    @Value("${app.payment.return-claim:true}")
    private boolean returnClaim;
    @Value("${app.payment.return-deep-link:ceta://payment/result}")
    private String deepLink;

    /** VNPay is always offered; MoMo only once its credentials are configured. */
    @GetMapping("/methods")
    @PreAuthorize("isAuthenticated()")
    public List<Map<String, Object>> methods() {
        MomoService service = momo.getIfAvailable();
        var qr = vietQr.getIfAvailable();
        return List.of(Map.of("code", "VNPAY", "enabled", true), Map.of("code", "MOMO", "enabled", service != null && service.enabled()),
                Map.of("code", "VIETQR", "enabled", qr != null && qr.enabled()));
    }

    /** MoMo posts JSON and wants HTTP 204 as the acknowledgement; anything else makes it retry. */
    @PostMapping("/momo/ipn")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> ipn(@RequestBody Map<String, Object> body) {
        PaymentOutcome outcome = applyPayment.applyMomo(text(body)).outcome();
        boolean accepted = outcome == PaymentOutcome.CONFIRMED || outcome == PaymentOutcome.PAYMENT_FAILED
                || outcome == PaymentOutcome.REFUND_QUEUED || outcome == PaymentOutcome.ALREADY_PROCESSED;
        return ResponseEntity.status(accepted ? HttpStatus.NO_CONTENT : HttpStatus.BAD_REQUEST).build();
    }

    /** The bank-feed aggregator posts each incoming transfer here; 200 {"success":true} acknowledges it, anything else makes it retry. */
    @PostMapping("/vietqr/webhook")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Map<String, Object>> vietQrWebhook(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
                                                             @RequestBody Map<String, Object> body) {
        var qr = vietQr.getIfAvailable();
        if (qr == null || !qr.verifyKey(authorization)) {
            log.warn("SECURITY VietQR webhook with a bad key");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        PaymentOutcome outcome = applyPayment.applyVietQr(text(body)).outcome();
        // A transfer that is not for a CETA order (no note, other account) must not be retried forever: acknowledge it.
        boolean retry = outcome == PaymentOutcome.INVALID_AMOUNT;
        log.info("VietQR webhook outcome={}", outcome);
        return ResponseEntity.status(retry ? HttpStatus.BAD_REQUEST : HttpStatus.OK).body(Map.of("success", !retry));
    }

    @GetMapping("/momo/return")
    @PreAuthorize("permitAll()")
    public ResponseEntity<Void> returnUrl(HttpServletRequest request) {
        Map<String, String> params = new LinkedHashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));
        PaymentResult result = returnClaim ? applyPayment.applyMomo(params) : null;
        String target = deepLink + "?result=" + (result == null ? "pending" : result.outcome().name().toLowerCase())
                + (result != null && result.bookingId() != null ? "&bookingId=" + result.bookingId() : "");
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, URI.create(target).toString()).build();
    }

    private static Map<String, String> text(Map<String, Object> body) {
        Map<String, String> out = new LinkedHashMap<>();
        body.forEach((k, v) -> { if (v != null) out.put(k, String.valueOf(v)); });
        return out;
    }
}
