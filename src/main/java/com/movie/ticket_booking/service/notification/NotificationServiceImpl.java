package com.movie.ticket_booking.service.notification;

import com.movie.ticket_booking.dto.booking.NotificationResponse;
import com.movie.ticket_booking.dto.booking.PageResponse;
import com.movie.ticket_booking.entity.Notification;
import com.movie.ticket_booking.entity.enums.NotificationType;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.NotificationRepository;
import com.movie.ticket_booking.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationServiceImpl implements NotificationService {
    private final NotificationRepository notifications;
    private final UserRepository users;
    private final PushSender push;
    private final NotificationWriter writer;

    @Override
    public boolean notify(Long userId, NotificationType type, Long bookingId, Object... args) {
        String[] text = NotificationTexts.of(type, bookingId, args);
        try {
            writer.insert(Notification.builder().userId(userId).type(type).title(text[0]).body(text[1])
                    .bookingId(bookingId).createdAt(Instant.now()).build());
        } catch (DataIntegrityViolationException | CannotAcquireLockException e) {
            return false; // the unique key says this user already has it (or a twin insert is committing it right now)
        }
        try {
            push.send(userId, text[0], text[1]);
        } catch (RuntimeException e) {
            log.warn("Push failed userId={} type={}", userId, type, e); // the in-app row is the record; push is best effort
        }
        return true;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResponse<NotificationResponse> list(String requesterEmail, int page, int size) {
        Long userId = userId(requesterEmail);
        Page<Notification> result = notifications.findByUserIdOrderByIdDesc(userId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50)));
        return new PageResponse<>(result.getContent().stream().map(NotificationServiceImpl::toResponse).toList(),
                result.getNumber(), result.getSize(), result.getTotalElements(), result.getTotalPages());
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount(String requesterEmail) {
        return notifications.countByUserIdAndReadAtIsNull(userId(requesterEmail));
    }

    @Override
    @Transactional
    public void markRead(String requesterEmail, Long id) {
        if (notifications.markRead(id, userId(requesterEmail), Instant.now()) == 0) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Notification not found");
        }
    }

    @Override
    @Transactional
    public int markAllRead(String requesterEmail) {
        return notifications.markAllRead(userId(requesterEmail), Instant.now());
    }

    private Long userId(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        return users.findByEmail(email.trim().toLowerCase()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found")).getId();
    }

    private static NotificationResponse toResponse(Notification n) {
        return new NotificationResponse(n.getId(), n.getType().name(), n.getTitle(), n.getBody(), n.getBookingId(),
                n.getCreatedAt(), n.getReadAt() != null);
    }
}
