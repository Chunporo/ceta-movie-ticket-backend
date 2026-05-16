package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.entity.Booking;
import com.movie.ticket_booking.entity.enums.BookingStatus;
import com.movie.ticket_booking.repository.BookingRepository;
import com.movie.ticket_booking.repository.TicketRepository;
import com.movie.ticket_booking.service.VNPayService;
import com.movie.ticket_booking.service.email.EmailService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Controller
@RequestMapping("/api/payment/vnpay")
@RequiredArgsConstructor
@Slf4j
public class VNPayCallbackController {

    private final VNPayService vnPayService;
    private final BookingRepository bookingRepository;
    private final TicketRepository ticketRepository;
    private final EmailService emailService;

    @Value("${app.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    /**
     * VNPay redirects user to this URL after payment.
     * Verifies signature, updates booking status, redirects to frontend result page.
     */
    @GetMapping("/callback")
    public String handleCallback(HttpServletRequest request) {
        Map<String, String> params = new HashMap<>();
        request.getParameterMap().forEach((k, v) -> params.put(k, v[0]));

        String txnRef      = params.get("vnp_TxnRef");
        String responseCode = params.get("vnp_ResponseCode");

        log.info("VNPay callback received: txnRef={} responseCode={}", txnRef, responseCode);

        boolean signatureValid = vnPayService.verifyCallback(params);
        if (!signatureValid) {
            log.warn("VNPay callback: invalid signature for txnRef={}", txnRef);
            return "redirect:" + frontendUrl + "/booking/result?status=error&reason=invalid_signature";
        }

        Long bookingId;
        try {
            String originalTxn = txnRef;
            // Extract the original booking ID by splitting off the timestamp suffix
            if (txnRef != null && txnRef.contains("_")) {
                originalTxn = txnRef.split("_")[0];
            }
            bookingId = Long.parseLong(originalTxn);
        } catch (NumberFormatException e) {
            log.error("Failed to parse bookingId from txnRef: {}", txnRef);
            return "redirect:" + frontendUrl + "/booking/result?status=error&reason=invalid_txn";
        }

        Booking booking = bookingRepository.findById(bookingId).orElse(null);
        if (booking == null) {
            return "redirect:" + frontendUrl + "/booking/result?status=error&reason=booking_not_found";
        }

        if ("00".equals(responseCode)) {
            // Payment success
            booking.setStatus(BookingStatus.PAID);
            booking.setPaidAt(Instant.now());
            bookingRepository.save(booking);

            // Send confirmation email async
            try {
                emailService.sendBookingConfirmation(booking,
                        ticketRepository.findByBookingIdOrderByIdAsc(bookingId));
            } catch (Exception ex) {
                log.warn("Email send failed for bookingId={}: {}", bookingId, ex.getMessage());
            }

            return "redirect:" + frontendUrl + "/booking/result?status=success&bookingId=" + bookingId;
        } else {
            // Payment failed or cancelled
            booking.setStatus(BookingStatus.CANCELLED);
            bookingRepository.save(booking);
            return "redirect:" + frontendUrl + "/booking/result?status=failed&bookingId=" + bookingId
                    + "&code=" + responseCode;
        }
    }
}
