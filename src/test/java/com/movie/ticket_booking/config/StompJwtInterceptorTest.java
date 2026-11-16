package com.movie.ticket_booking.config;

import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.BadJwtException;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StompJwtInterceptorTest {
    private final JwtDecoder decoder = token -> {
        if (!"good".equals(token)) throw new BadJwtException("bad");
        return new Jwt("good", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "HS256"), Map.of("sub", "a@b.c"));
    };
    private final StompJwtInterceptor interceptor = new StompJwtInterceptor(decoder);

    private static Message<byte[]> frame(StompCommand command, String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        if (authorization != null) accessor.addNativeHeader("Authorization", authorization);
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    @Test
    void connectWithoutOrWithBadTokenIsRejected() {
        assertThrows(MessagingException.class, () -> interceptor.preSend(frame(StompCommand.CONNECT, null), null));
        assertThrows(MessagingException.class, () -> interceptor.preSend(frame(StompCommand.CONNECT, "Bearer "), null));
        assertThrows(MessagingException.class, () -> interceptor.preSend(frame(StompCommand.CONNECT, "Basic abc"), null));
        assertThrows(MessagingException.class, () -> interceptor.preSend(frame(StompCommand.CONNECT, "Bearer nope"), null));
    }

    @Test
    void connectWithAValidTokenPassesAndCarriesTheUser() {
        Message<?> result = interceptor.preSend(frame(StompCommand.CONNECT, "Bearer good"), null);

        assertNotNull(result);
        assertEquals("a@b.c", StompHeaderAccessor.wrap(result).getUser().getName());
    }

    @Test
    void otherFramesAreNotTouched() {
        Message<byte[]> subscribe = frame(StompCommand.SUBSCRIBE, null);
        assertSame(subscribe, interceptor.preSend(subscribe, null));
    }
}
