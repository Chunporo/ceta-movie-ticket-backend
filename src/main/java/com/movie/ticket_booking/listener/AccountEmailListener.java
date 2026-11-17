package com.movie.ticket_booking.listener;

import com.movie.ticket_booking.event.PasswordResetRequestedEvent;
import com.movie.ticket_booking.repository.UserRepository;
import com.movie.ticket_booking.service.email.EmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Component
@Slf4j
@RequiredArgsConstructor
public class AccountEmailListener {
    private final UserRepository users;
    private final EmailService email;

    @Value("${app.auth.reset-link-template:ceta://reset-password?token=%s}")
    private String linkTemplate;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void onPasswordResetRequested(PasswordResetRequestedEvent event) {
        users.findById(event.userId()).ifPresent(user -> {
            try {
                email.sendPasswordReset(user, String.format(linkTemplate, URLEncoder.encode(event.token(), StandardCharsets.UTF_8)));
            } catch (RuntimeException ex) {
                log.warn("Password reset email failed userId={}", user.getId(), ex);
            }
        });
    }
}
