package com.movie.ticket_booking.service.payment.momo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class MomoServiceTest {
    private MomoGateway gateway;
    private MomoService momo;

    @BeforeEach
    void setUp() {
        gateway = mock(MomoGateway.class);
        momo = new MomoService(gateway);
        ReflectionTestUtils.setField(momo, "partnerCode", "MOMOTEST");
        ReflectionTestUtils.setField(momo, "accessKey", "access-1");
        ReflectionTestUtils.setField(momo, "secretKey", "secret-1");
        ReflectionTestUtils.setField(momo, "endpoint", "http://momo.test/create");
        ReflectionTestUtils.setField(momo, "redirectUrl", "http://10.0.2.2:8080/api/v1/payments/momo/return");
        ReflectionTestUtils.setField(momo, "ipnUrl", "http://host/api/v1/payments/momo/ipn");
        ReflectionTestUtils.setField(momo, "partnerName", "CETA");
    }

    /** What MoMo would send back: the same fields, signed with the merchant's secret. */
    private Map<String, String> callback() {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("partnerCode", "MOMOTEST"); p.put("orderId", "5_1"); p.put("requestId", "r1"); p.put("amount", "170000");
        p.put("orderInfo", "Dat ve phim #5"); p.put("orderType", "momo_wallet"); p.put("transId", "4086000001"); p.put("resultCode", "0");
        p.put("message", "Successful."); p.put("payType", "qr"); p.put("responseTime", "1763394000000"); p.put("extraData", "");
        String raw = "accessKey=access-1&amount=170000&extraData=&message=Successful.&orderId=5_1&orderInfo=Dat ve phim #5&orderType=momo_wallet"
                + "&partnerCode=MOMOTEST&payType=qr&requestId=r1&responseTime=1763394000000&resultCode=0&transId=4086000001";
        p.put("signature", MomoService.hmacSha256("secret-1", raw));
        return p;
    }

    @Test
    void isOffUntilEveryMerchantSettingIsPresent() {
        assertThat(momo.enabled()).isTrue();
        ReflectionTestUtils.setField(momo, "secretKey", "");
        assertThat(momo.enabled()).isFalse();
        assertThat(momo.verifyCallback(callback())).isFalse();
    }

    @Test
    void aCorrectlySignedCallbackVerifiesAndAnyChangeBreaksIt() {
        assertThat(momo.verifyCallback(callback())).isTrue();

        Map<String, String> cheaper = callback();
        cheaper.put("amount", "1000");
        assertThat(momo.verifyCallback(cheaper)).isFalse();

        Map<String, String> flipped = callback();
        flipped.put("resultCode", "1006");
        assertThat(momo.verifyCallback(flipped)).isFalse();

        Map<String, String> unsigned = callback();
        unsigned.remove("signature");
        assertThat(momo.verifyCallback(unsigned)).isFalse();
    }

    @Test
    void theHmacMatchesAnIndependentImplementation() {
        // Expected value computed with Python's hmac/hashlib (HMAC-SHA256, lowercase hex).
        assertThat(MomoService.hmacSha256("secret-1", "accessKey=access-1&amount=1")).isEqualTo("81a505afb3c3324a2bd1769a05630188145d91d3c961879ad5b58c1c18e64c26");
    }

    @Test
    void onlyOurOwnPartnerCodeIsAccepted() {
        assertThat(momo.isOwnPartner("MOMOTEST")).isTrue();
        assertThat(momo.isOwnPartner("SOMEONEELSE")).isFalse();
        assertThat(momo.isOwnPartner(null)).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void creatingAPaymentSignsTheRequestAndReturnsMomosPayUrl() {
        when(gateway.create(eq("http://momo.test/create"), any())).thenReturn(new MomoGateway.CreateResult(0, "Successful.", "http://pay.test/x"));

        String url = momo.createPayUrl("5_1", 170000, "Dat ve phim #5");

        assertThat(url).isEqualTo("http://pay.test/x");
        var body = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(gateway).create(eq("http://momo.test/create"), body.capture());
        Map<String, Object> sent = body.getValue();
        assertThat(sent).containsEntry("partnerCode", "MOMOTEST").containsEntry("orderId", "5_1").containsEntry("amount", 170000L)
                .containsEntry("requestType", "captureWallet").containsEntry("redirectUrl", "http://10.0.2.2:8080/api/v1/payments/momo/return");
        String raw = "accessKey=access-1&amount=170000&extraData=&ipnUrl=http://host/api/v1/payments/momo/ipn&orderId=5_1&orderInfo=Dat ve phim #5"
                + "&partnerCode=MOMOTEST&redirectUrl=http://10.0.2.2:8080/api/v1/payments/momo/return&requestId=" + sent.get("requestId") + "&requestType=captureWallet";
        assertThat(sent.get("signature")).isEqualTo(MomoService.hmacSha256("secret-1", raw));
        assertThat(sent).doesNotContainKey("secretKey").doesNotContainKey("accessKey");
    }

    @Test
    void aRefusalFromMomoIsAFailureNotAPayUrl() {
        when(gateway.create(any(), any())).thenReturn(new MomoGateway.CreateResult(1001, "Insufficient", null));
        assertThatThrownBy(() -> momo.createPayUrl("5_1", 170000, "x")).hasMessageContaining("1001");
    }
}
