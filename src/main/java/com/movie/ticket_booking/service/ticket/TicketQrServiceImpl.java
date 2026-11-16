package com.movie.ticket_booking.service.ticket;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

@Service
public class TicketQrServiceImpl implements TicketQrService {
    private static final String VERSION = "CETA1";

    private final byte[] key;

    /** {@code app.ticket.qr-secret} should be its own secret; it falls back to the JWT secret so dev needs no extra setup. */
    public TicketQrServiceImpl(@Value("${app.ticket.qr-secret:${app.jwt.secret}}") String secret) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("app.ticket.qr-secret (or app.jwt.secret) must be at least 32 bytes");
        }
        this.key = secret.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String sign(Long ticketId, Instant expiresAt) {
        long exp = expiresAt.getEpochSecond();
        return VERSION + "." + ticketId + "." + exp + "." + mac(ticketId, exp);
    }

    @Override
    public Long verify(String payload) {
        if (payload == null) {
            throw new InvalidQrException("empty");
        }
        String[] parts = payload.trim().split("\\.", -1);
        if (parts.length != 4 || !VERSION.equals(parts[0])) {
            throw new InvalidQrException("malformed");
        }
        long ticketId;
        long exp;
        try {
            ticketId = Long.parseLong(parts[1]);
            exp = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            throw new InvalidQrException("malformed");
        }
        boolean genuine = MessageDigest.isEqual(mac(ticketId, exp).getBytes(StandardCharsets.US_ASCII),
                parts[3].getBytes(StandardCharsets.US_ASCII));
        if (!genuine) {
            throw new InvalidQrException("bad signature");
        }
        if (Instant.now().getEpochSecond() > exp) {
            throw new InvalidQrException("expired");
        }
        return ticketId;
    }

    private String mac(Long ticketId, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal((VERSION + "|" + ticketId + "|" + exp).getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }
}
