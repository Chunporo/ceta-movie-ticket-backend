package com.movie.ticket_booking.service.payment.momo;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.Map;

@Component
public class HttpMomoGateway implements MomoGateway {
    private final RestClient client;

    public HttpMomoGateway() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(10).toMillis());
        this.client = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    @SuppressWarnings("unchecked")
    public CreateResult create(String endpoint, Map<String, Object> body) {
        Map<String, Object> answer = client.post().uri(endpoint).contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().body(Map.class);
        if (answer == null) {
            throw new IllegalStateException("Empty answer from MoMo");
        }
        Object code = answer.get("resultCode");
        return new CreateResult(code instanceof Number n ? n.intValue() : -1,
                String.valueOf(answer.get("message")), answer.get("payUrl") == null ? null : String.valueOf(answer.get("payUrl")));
    }
}
