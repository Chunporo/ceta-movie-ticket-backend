package com.movie.ticket_booking.service.payment.momo;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MoMo "captureWallet" (v2): the backend asks MoMo for a pay URL, the customer pays there, MoMo then calls the IPN
 * (server to server) and redirects the browser. Every message is signed with HMAC-SHA256 over a fixed, alphabetical
 * list of {@code key=value} pairs that starts with the merchant's access key. Switched off while no partner code is set.
 *
 * <p>The field lists follow MoMo's v2 documentation as recalled when this was written; check them against the current
 * docs when real sandbox credentials arrive (the local simulator mirrors this code, so it cannot catch a mistake here).
 */
@Component
@Slf4j
public class MomoService {
    static final List<String> CREATE_FIELDS = List.of("accessKey", "amount", "extraData", "ipnUrl", "orderId", "orderInfo",
            "partnerCode", "redirectUrl", "requestId", "requestType");
    /** The same list signs the IPN body and the redirect query. */
    static final List<String> CALLBACK_FIELDS = List.of("accessKey", "amount", "extraData", "message", "orderId", "orderInfo",
            "orderType", "partnerCode", "payType", "requestId", "responseTime", "resultCode", "transId");

    private final MomoGateway gateway;

    @Value("${momo.partner-code:}")
    private String partnerCode;
    @Value("${momo.access-key:}")
    private String accessKey;
    @Value("${momo.secret-key:}")
    private String secretKey;
    @Value("${momo.endpoint:https://test-payment.momo.vn/v2/gateway/api/create}")
    private String endpoint;
    @Value("${momo.redirect-url:}")
    private String redirectUrl;
    @Value("${momo.ipn-url:}")
    private String ipnUrl;
    @Value("${momo.partner-name:CETA}")
    private String partnerName;

    public MomoService(MomoGateway gateway) {
        this.gateway = gateway;
    }

    public boolean enabled() {
        return !partnerCode.isBlank() && !accessKey.isBlank() && !secretKey.isBlank() && !redirectUrl.isBlank() && !ipnUrl.isBlank();
    }

    /** Asks MoMo for a pay URL for one attempt. {@code orderId} is our txn_ref, {@code amountVnd} whole dong. */
    public String createPayUrl(String orderId, long amountVnd, String orderInfo) {
        Map<String, String> signed = new LinkedHashMap<>();
        signed.put("accessKey", accessKey);
        signed.put("amount", String.valueOf(amountVnd));
        signed.put("extraData", "");
        signed.put("ipnUrl", ipnUrl);
        signed.put("orderId", orderId);
        signed.put("orderInfo", orderInfo);
        signed.put("partnerCode", partnerCode);
        signed.put("redirectUrl", redirectUrl);
        signed.put("requestId", UUID.randomUUID().toString());
        signed.put("requestType", "captureWallet");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerCode", partnerCode);
        body.put("partnerName", partnerName);
        body.put("requestId", signed.get("requestId"));
        body.put("amount", amountVnd);
        body.put("orderId", orderId);
        body.put("orderInfo", orderInfo);
        body.put("redirectUrl", redirectUrl);
        body.put("ipnUrl", ipnUrl);
        body.put("lang", "vi");
        body.put("requestType", "captureWallet");
        body.put("autoCapture", true);
        body.put("extraData", "");
        body.put("signature", sign(signed, CREATE_FIELDS));

        MomoGateway.CreateResult result = gateway.create(endpoint, body);
        if (result.resultCode() != 0 || result.payUrl() == null || result.payUrl().isBlank()) {
            throw new IllegalStateException("MoMo refused the payment: resultCode=" + result.resultCode() + " " + result.message());
        }
        log.info("MoMo payment created orderId={} amount={}", orderId, amountVnd);
        return result.payUrl();
    }

    /** IPN body or redirect query: valid only when the signature matches the received fields. */
    public boolean verifyCallback(Map<String, String> params) {
        String received = params.get("signature");
        if (received == null || received.isBlank() || !enabled()) {
            return false;
        }
        String expected = sign(params, CALLBACK_FIELDS);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), received.trim().toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    public boolean isOwnPartner(String code) {
        return code != null && enabled() && partnerCode.trim().equals(code.trim());
    }

    private String sign(Map<String, String> values, List<String> fields) {
        StringBuilder raw = new StringBuilder();
        for (String field : fields) {
            if (raw.length() > 0) raw.append('&');
            String value = "accessKey".equals(field) ? accessKey : values.get(field);
            raw.append(field).append('=').append(value == null ? "" : value);
        }
        return hmacSha256(secretKey.trim(), raw.toString());
    }

    public static String hmacSha256(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            StringBuilder hex = new StringBuilder();
            for (byte b : mac.doFinal(data.getBytes(StandardCharsets.UTF_8))) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }
}
