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

    public boolean isOwnAccount(String account) {
        return account == null || account.isBlank() || account.trim().equals(accountNo.trim());
    }

    /** The txn_ref (bookingId_attempt) found in a transfer's text, or null. */
    public static String txnRefIn(Map<String, String> payload) {
        Matcher m = NOTE.matcher(payload.getOrDefault("content", "") + " " + payload.getOrDefault("description", ""));
        return m.find() ? m.group(1) + "_" + m.group(2) : null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
