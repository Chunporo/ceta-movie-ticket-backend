package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.config.SecurityConfig;
import com.movie.ticket_booking.dto.ReviewResponse;
import com.movie.ticket_booking.dto.booking.PageResponse;
import com.movie.ticket_booking.service.ReminderService;
import com.movie.ticket_booking.service.ReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ReviewController.class, ReminderController.class})
@Import(SecurityConfig.class)
class ReviewControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private ReviewService reviewService;
    @MockBean private ReminderService reminderService;

    private final UUID movie = UUID.randomUUID();

    @Test
    void anyoneCanReadReviewsAndASignedInCallerIsPassedAlong() throws Exception {
        PageResponse<ReviewResponse> page = new PageResponse<>(
                List.of(new ReviewResponse(1L, 9, "Loved it", "Ng***", true, Instant.parse("2026-11-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"))), 0, 20, 1, 1);
        when(reviewService.list(movie, null, 0, 20)).thenReturn(page);
        when(reviewService.list(movie, "a@b.vn", 0, 20)).thenReturn(page);

        mockMvc.perform(get("/api/v1/movies/{id}/reviews", movie)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].author").value("Ng***")).andExpect(jsonPath("$.items[0].rating").value(9));
        mockMvc.perform(get("/api/v1/movies/{id}/reviews", movie).with(jwt().jwt(j -> j.subject("a@b.vn")))).andExpect(status().isOk());
        verify(reviewService).list(movie, "a@b.vn", 0, 20);
    }

    @Test
    void aCustomerCanWriteAndDeleteTheirReviewEvenThoughMovieWritesAreAdminOnly() throws Exception {
        when(reviewService.save(any(), any(), any())).thenReturn(new ReviewResponse(1L, 8, null, "Ng***", true, Instant.now(), Instant.now()));

        mockMvc.perform(put("/api/v1/movies/{id}/review", movie).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")).jwt(j -> j.subject("a@b.vn")))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rating\":8}"))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/movies/{id}/review", movie).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")).jwt(j -> j.subject("a@b.vn"))))
                .andExpect(status().isNoContent());
        verify(reviewService).delete("a@b.vn", movie);
    }

    @Test
    void ratingsOutsideOneToTenAreRejectedAndAnonymousIsTurnedAway() throws Exception {
        for (String body : List.of("{\"rating\":0}", "{\"rating\":11}", "{}")) {
            mockMvc.perform(put("/api/v1/movies/{id}/review", movie).with(jwt().jwt(j -> j.subject("a@b.vn")))
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        mockMvc.perform(put("/api/v1/movies/{id}/review", movie).contentType(MediaType.APPLICATION_JSON).content("{\"rating\":8}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(reviewService);
    }

    @Test
    void remindersAreForSignedInCustomers() throws Exception {
        mockMvc.perform(put("/api/v1/movies/{id}/reminder", movie).with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")).jwt(j -> j.subject("a@b.vn"))))
                .andExpect(status().isNoContent());
        verify(reminderService).add("a@b.vn", movie);
        mockMvc.perform(delete("/api/v1/movies/{id}/reminder", movie).with(jwt().jwt(j -> j.subject("a@b.vn")))).andExpect(status().isNoContent());
        mockMvc.perform(put("/api/v1/movies/{id}/reminder", movie)).andExpect(status().isUnauthorized());
        when(reminderService.mine("a@b.vn")).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/reminders").with(jwt().jwt(j -> j.subject("a@b.vn")))).andExpect(status().isOk());
    }
}
