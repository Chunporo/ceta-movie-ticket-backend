package com.movie.ticket_booking.service.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Default until a push provider exists; a real sender replaces it (mark it @Primary). */
@Component
@Slf4j
public class LoggingPushSender implements PushSender {
    @Override
    public void send(Long userId, String title, String body) {
        log.debug("Push disabled: skip userId={} title={}", userId, title);
    }
}
