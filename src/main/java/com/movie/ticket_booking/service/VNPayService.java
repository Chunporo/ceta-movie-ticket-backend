package com.movie.ticket_booking.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

@Service
@Slf4j
public class VNPayService {

    @Value("${vnpay.tmn-code}")
    private String tmnCode;

    @Value("${vnpay.hash-secret}")
    private String hashSecret;

    @Value("${vnpay.url}")
    private String vnpayUrl;

    @Value("${vnpay.return-url}")
    private String returnUrl;

    /** Legacy entry point (old checkout flow): timestamp-based reference, 15 minute window. */
    public String buildPaymentUrl(Long bookingId, BigDecimal amount, String orderInfo, String ipAddr) {
        return buildPaymentUrl(bookingId + "_" + System.currentTimeMillis(), amount.longValue(), orderInfo, ipAddr,
                java.time.Instant.now().plus(java.time.Duration.ofMinutes(15)));
    }

    /**
     * @param vnpTxnRef our unique reference, {@code bookingId_attemptNo} (D-09)
     * @param amountVnd whole dong; sent to VNPay as amount x 100
     * @param expiresAt when VNPay stops accepting payment (the booking's hold expiry)
     */
    public String buildPaymentUrl(String vnpTxnRef, long amountVnd, String orderInfo, String ipAddr, java.time.Instant expiresAt) {
        String vnpOrderInfo = orderInfo == null || orderInfo.isBlank() ? "Dat ve phim " + vnpTxnRef : orderInfo.trim();

        // 3. Amount format: vnp_Amount = amount * 100 (integer string)
        long vnpAmount = Math.multiplyExact(amountVnd, 100L);

        // 4. Timezone: Asia/Ho_Chi_Minh (GMT+7)
        // VNPAY documentation wrongly uses "Etc/GMT+7" which in Java means GMT-7.
        Calendar cld = Calendar.getInstance(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
        SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddHHmmss");
        formatter.setTimeZone(TimeZone.getTimeZone("Asia/Ho_Chi_Minh"));
        String vnpCreateDate = formatter.format(cld.getTime());
        
        String vnpExpireDate = formatter.format(Date.from(expiresAt));

        // 6. IP address: convert localhost IPv6 to IPv4
        String vnpIpAddr = ipAddr;
        if (vnpIpAddr == null || vnpIpAddr.equals("0:0:0:0:0:0:0:1") || vnpIpAddr.equals("127.0.0.1")) {
            vnpIpAddr = "127.0.0.1";
        }

        Map<String, String> vnp_Params = new HashMap<>();
        vnp_Params.put("vnp_Version", "2.1.0");
        vnp_Params.put("vnp_Command", "pay");
        vnp_Params.put("vnp_TmnCode", tmnCode != null ? tmnCode.trim() : "");
        vnp_Params.put("vnp_Amount", String.valueOf(vnpAmount));
        vnp_Params.put("vnp_CurrCode", "VND");
        vnp_Params.put("vnp_TxnRef", vnpTxnRef);
        vnp_Params.put("vnp_OrderInfo", vnpOrderInfo);
        vnp_Params.put("vnp_OrderType", "other");
        vnp_Params.put("vnp_Locale", "vn");
        vnp_Params.put("vnp_ReturnUrl", returnUrl != null ? returnUrl.trim() : "");
        vnp_Params.put("vnp_IpAddr", vnpIpAddr.trim());
        vnp_Params.put("vnp_CreateDate", vnpCreateDate);
        vnp_Params.put("vnp_ExpireDate", vnpExpireDate);

        // 7. COMPLETE DEBUG LOGS
        log.info("--- VNPAY INIT RAW PARAMS ---");
        vnp_Params.forEach((k, v) -> log.info("{} = {}", k, v));

        // 2. params sorted alphabetically
        List<String> fieldNames = new ArrayList<>(vnp_Params.keySet());
        Collections.sort(fieldNames);

        StringBuilder hashData = new StringBuilder();
        StringBuilder query = new StringBuilder();
        Iterator<String> itr = fieldNames.iterator();
        while (itr.hasNext()) {
            String fieldName = itr.next();
            String fieldValue = vnp_Params.get(fieldName);
            
            if ((fieldValue != null) && (!fieldValue.isEmpty())) {
                try {
                    // Build hash data EXACTLY as per official Java documentation
                    hashData.append(fieldName);
                    hashData.append('=');
                    hashData.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII.toString()));
                    
                    // Build query EXACTLY as per official Java documentation
                    query.append(URLEncoder.encode(fieldName, StandardCharsets.US_ASCII.toString()));
                    query.append('=');
                    query.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII.toString()));
                } catch (Exception e) {
                    log.error("Encoding error", e);
                }
                
                if (itr.hasNext()) {
                    query.append('&');
                    hashData.append('&');
                }
            }
        }

        String queryUrl = query.toString();
        String signData = hashData.toString();
        
        log.info("--- VNPAY SORTED & ENCODED HASHDATA ---");
        log.info(signData);

        // 2. Hash using HmacSHA512. Final secure hash should be LOWERCASE HEX
        String vnp_SecureHash = hmacSHA512(hashSecret, signData);
        log.info("--- VNPAY LOWERCASE SECURE HASH ---");
        log.info(vnp_SecureHash);

        queryUrl += "&vnp_SecureHash=" + vnp_SecureHash;
        String paymentUrl = vnpayUrl + "?" + queryUrl;

        log.info("--- VNPAY FINAL PAYMENT URL ---");
        log.info(paymentUrl);

        return paymentUrl;
    }

    public boolean verifyCallback(Map<String, String> params) {
        String receivedHash = params.get("vnp_SecureHash");
        if (receivedHash == null || receivedHash.isBlank()) return false;

        // 8. Verify callback signature correctly
        Map<String, String> vnp_Params = new HashMap<>(params);
        vnp_Params.remove("vnp_SecureHash");
        vnp_Params.remove("vnp_SecureHashType");

        List<String> fieldNames = new ArrayList<>(vnp_Params.keySet());
        Collections.sort(fieldNames);

        StringBuilder hashData = new StringBuilder();
        Iterator<String> itr = fieldNames.iterator();
        while (itr.hasNext()) {
            String fieldName = itr.next();
            String fieldValue = vnp_Params.get(fieldName);
            
            if ((fieldValue != null) && (!fieldValue.isEmpty())) {
                try {
                    hashData.append(fieldName);
                    hashData.append('=');
                    hashData.append(URLEncoder.encode(fieldValue, StandardCharsets.US_ASCII.toString()));
                } catch (Exception e) {}
                
                if (itr.hasNext()) {
                    hashData.append('&');
                }
            }
        }

        String signData = hashData.toString();
        log.info("--- VNPAY CALLBACK VERIFY HASHDATA ---");
        log.info(signData);

        String expectedHash = hmacSHA512(hashSecret, signData);
        
        // Compare hashes case-insensitively
        boolean valid = expectedHash.equalsIgnoreCase(receivedHash);
        log.info("VNPay callback verify: txnRef={} valid={}", params.get("vnp_TxnRef"), valid);
                
        return valid;
    }

    public boolean isOwnTmnCode(String code) {
        return code != null && tmnCode != null && tmnCode.trim().equals(code.trim());
    }

    public static String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("Proxy-Client-IP");
        }
        if (ip == null || ip.isBlank() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) ip = ip.split(",")[0].trim();
        return ip;
    }

    private String hmacSHA512(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            // Trim the key to ensure no accidental newlines/spaces break the hash
            SecretKeySpec secretKeySpec = new SecretKeySpec(key.trim().getBytes(StandardCharsets.US_ASCII), "HmacSHA512");
            mac.init(secretKeySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.US_ASCII));
            
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                // LOWERCASE HEX format (%02x) as requested
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC-SHA512", e);
        }
    }
}

