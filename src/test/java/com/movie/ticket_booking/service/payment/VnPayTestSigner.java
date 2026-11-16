package com.movie.ticket_booking.service.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/** Signs callbacks exactly as VNPayService verifies them. */
final class VnPayTestSigner {
    private VnPayTestSigner() {}

    static Map<String, String> signed(String secret, String tmnCode, String txnRef, long vnpAmount,
                                      String responseCode, String transactionStatus) {
        Map<String, String> params = new TreeMap<>();
        params.put("vnp_TmnCode", tmnCode);
        params.put("vnp_TxnRef", txnRef);
        params.put("vnp_Amount", String.valueOf(vnpAmount));
        params.put("vnp_ResponseCode", responseCode);
        params.put("vnp_TransactionStatus", transactionStatus);
        params.put("vnp_TransactionNo", "14000003");
        StringBuilder data = new StringBuilder();
        params.forEach((k, v) -> data.append(data.length() == 0 ? "" : "&").append(k).append('=')
                .append(URLEncoder.encode(v, StandardCharsets.US_ASCII)));
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secret.trim().getBytes(StandardCharsets.US_ASCII), "HmacSHA512"));
            StringBuilder hex = new StringBuilder();
            for (byte b : mac.doFinal(data.toString().getBytes(StandardCharsets.US_ASCII))) hex.append(String.format("%02x", b));
            params.put("vnp_SecureHash", hex.toString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new LinkedHashMap<>(params);
    }
}
