package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.config.SecurityConfig;
import com.movie.ticket_booking.dto.booking.NotificationResponse;
import com.movie.ticket_booking.dto.booking.PageResponse;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.service.BookingService;
import com.movie.ticket_booking.service.notification.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(NotificationController.class)
@Import(SecurityConfig.class)
class NotificationControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private NotificationService notifications;
    @MockBean private BookingService bookingService;

    @Test
    void listReturnsThePageForTheCaller() throws Exception {
        when(notifications.list("a@b.c", 0, 20)).thenReturn(new PageResponse<>(List.of(new NotificationResponse(
                7L, "ORDER_PAID", "Đặt vé thành công", "Đơn #3", 3L, Instant.parse("2030-01-01T00:00:00Z"), false)), 0, 20, 1, 1));

        mockMvc.perform(get("/api/v1/notifications").with(jwt().jwt(j -> j.subject("a@b.c"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].type").value("ORDER_PAID"))
                .andExpect(jsonPath("$.items[0].read").value(false))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void unreadCountAndReadActions() throws Exception {
        when(notifications.unreadCount("a@b.c")).thenReturn(4L);

        mockMvc.perform(get("/api/v1/notifications/unread-count").with(jwt().jwt(j -> j.subject("a@b.c"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.unread").value(4));
        mockMvc.perform(put("/api/v1/notifications/7/read").with(jwt().jwt(j -> j.subject("a@b.c")))).andExpect(status().isNoContent());
        mockMvc.perform(put("/api/v1/notifications/read-all").with(jwt().jwt(j -> j.subject("a@b.c")))).andExpect(status().isNoContent());
        verify(notifications).markRead("a@b.c", 7L);
        verify(notifications).markAllRead("a@b.c");
    }

    @Test
    void someoneElsesNotificationIs404() throws Exception {
        doThrow(new BusinessException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "nope")).when(notifications).markRead(any(), any());

        mockMvc.perform(put("/api/v1/notifications/9/read").with(jwt().jwt(j -> j.subject("a@b.c"))))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    void anonymousIs401() throws Exception {
        mockMvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized());
        mockMvc.perform(put("/api/v1/notifications/read-all")).andExpect(status().isUnauthorized());
        verifyNoInteractions(notifications);
    }
}
