package com.movie.ticket_booking.service.payment.vietqr;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VietQR bank transfer: the QR is an image from img.vietqr.io (bank, account, amount and transfer note baked in); the
 * customer pays from any banking app. Nothing calls us at checkout, so a bank-feed aggregator (SePay, Casso, ...) posts
 * the incoming transfer to our webhook and we match it by the transfer note {@code CETA<bookingId>A<attemptNo>} and the exact amount.
 * Off until bank id, account number and webhook key are all set.
 *
 * <p>The webhook shape follows SePay's documented payload as recalled ({@code Authorization: Apikey <key>},
 * {@code transferType}, {@code transferAmount}, {@code content}, {@code referenceCode}); verify against the real service.
 */
@Component
public class VietQrService {
    private static final Pattern NOTE = Pattern.compile("CETA(\\d+)A(\\d+)", Pattern.CASE_INSENSITIVE);

    @Value("${vietqr.bank-id:}")
    private String bankId;
    @Value("${vietqr.account-no:}")
    private String accountNo;
    @Value("${vietqr.account-name:}")
    private String accountName;
    @Value("${vietqr.webhook-key:}")
    private String webhookKey;
    @Value("${vietqr.image-base:https://img.vietqr.io/image}")
    private String imageBase;

    /** What VietQR is given to call us back with (Basic auth on the token endpoint); both empty = that callback is off. */
    @Value("${vietqr.callback-username:}")
    private String callbackUsername;
    @Value("${vietqr.callback-password:}")
    private String callbackPassword;
    @Value("${vietqr.token-ttl-seconds:300}")
    private long tokenTtlSeconds;

    public boolean callbackEnabled() {
        return enabled() && !callbackUsername.isBlank() && !callbackPassword.isBlank();
    }

    public long tokenTtlSeconds() {
        return tokenTtlSeconds;
    }

    public boolean enabled() {
        return !bankId.isBlank() && !accountNo.isBlank() && !webhookKey.isBlank();
    }

    /** What the customer types (or the QR carries) as the transfer note; letters and digits only, as banks strip the rest. */
    public static String note(String txnRef) {
        return "CETA" + txnRef.replace("_", "A");
    }

    public String imageUrl(String txnRef, long amountVnd) {
        return imageBase + "/" + bankId + "-" + accountNo + "-compact2.png?amount=" + amountVnd
                + "&addInfo=" + enc(note(txnRef)) + (accountName.isBlank() ? "" : "&accountName=" + enc(accountName));
    }

    /** Header looks like {@code Apikey <key>}; compared in constant time. */
    public boolean verifyKey(String authorization) {
        if (!enabled() || authorization == null) return false;
        String given = authorization.regionMatches(true, 0, "Apikey ", 0, 7) ? authorization.substring(7).trim() : authorization.trim();
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), webhookKey.getBytes(StandardCharsets.UTF_8));
    }

    /** {@code Authorization: Basic base64(username:password)} on the token endpoint. */
    public boolean verifyBasic(String authorization) {
        if (!callbackEnabled() || authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) return false;
        String expected = callbackUsername + ":" + callbackPassword;
        try {
            String given = new String(java.util.Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
            return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** A short-lived bearer token for VietQR's transaction-sync call: {@code <expiryEpochSeconds>.<hmac>}, nothing stored. */
    public String issueToken(java.time.Instant now) {
        String exp = Long.toString(now.getEpochSecond() + tokenTtlSeconds);
        return exp + "." + com.movie.ticket_booking.service.payment.momo.MomoService.hmacSha256(callbackPassword, "vietqr-callback:" + exp);
    }

    public boolean verifyBearer(String authorization, java.time.Instant now) {
        if (!callbackEnabled() || authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        String token = authorization.substring(7).trim();
        int dot = token.indexOf('.');
        if (dot < 1) return false;
        String exp = token.substring(0, dot);
        try {
            if (Long.parseLong(exp) < now.getEpochSecond()) return false;
        } catch (NumberFormatException e) {
            return false;
        }
        String expected = com.movie.ticket_booking.service.payment.momo.MomoService.hmacSha256(callbackPassword, "vietqr-callback:" + exp);
        return MessageDigest.isEqual(token.substring(dot + 1).getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * VietQR's {@code transaction-sync} body ({@code bankaccount, amount, transType C|D, content, transactionid, referencenumber,
     * orderId, ...}) in the shape the settlement use case reads. Field names are matched ignoring case (the docs mix
     * {@code bankaccount} and {@code bankAccount}); {@code orderId} wins over the note when it is one of our txn_refs.
     */
    public static Map<String, String> fromTransactionSync(Map<String, String> body) {
        Map<String, String> lower = new java.util.HashMap<>();
        body.forEach((k, v) -> lower.put(k.toLowerCase(java.util.Locale.ROOT), v));
        Map<String, String> out = new java.util.LinkedHashMap<>();
        out.put("accountNumber", lower.getOrDefault("bankaccount", ""));
        out.put("transferAmount", lower.getOrDefault("amount", ""));
        out.put("transferType", "D".equalsIgnoreCase(lower.getOrDefault("transtype", "C")) ? "out" : "in");
        out.put("content", lower.getOrDefault("content", ""));
        out.put("referenceCode", lower.getOrDefault("referencenumber", lower.getOrDefault("transactionid", "")));
        String orderId = lower.getOrDefault("orderid", "").trim();
        if (orderId.matches("\\d+_\\d+")) out.put("orderId", orderId);
        return out;
    }

    public boolean isOwnAccount(String account) {
        return account == null || account.isBlank() || account.trim().equals(accountNo.trim());
    }

    /** The txn_ref (bookingId_attempt) found in a transfer's text, or null. */
    public static String txnRefIn(Map<String, String> payload) {
        String orderId = payload.get("orderId");
        if (orderId != null && orderId.matches("\\d+_\\d+")) return orderId;
        Matcher m = NOTE.matcher(payload.getOrDefault("content", "") + " " + payload.getOrDefault("code", "") + " " + payload.getOrDefault("description", ""));
        return m.find() ? m.group(1) + "_" + m.group(2) : null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
