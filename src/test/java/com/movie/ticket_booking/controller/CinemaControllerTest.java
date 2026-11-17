package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.config.SecurityConfig;
import com.movie.ticket_booking.dto.CinemaRequest;
import com.movie.ticket_booking.dto.CinemaResponse;
import com.movie.ticket_booking.service.CinemaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CinemaController.class)
@Import(SecurityConfig.class)
class CinemaControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private CinemaService cinemaService;

    private static CinemaResponse landmark() {
        return new CinemaResponse(1L, "LANDMARK", "CETA Landmark", "HCM", "208 Nguyễn Hữu Cảnh, Bình Thạnh",
                new BigDecimal("10.794700"), new BigDecimal("106.721900"), true, true);
    }

    private static final String VALID = """
            {"code":"NEW1","name":"CETA New","city":"hcm","address":"1 Main St","latitude":10.5,"longitude":106.5}""";

    @Test
    void anyoneCanListCinemasOfACity() throws Exception {
        when(cinemaService.list("HCM")).thenReturn(List.of(landmark()));

        mockMvc.perform(get("/api/v1/cinemas").param("city", "HCM").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("CETA Landmark"))
                .andExpect(jsonPath("$[0].imax").value(true))
                .andExpect(jsonPath("$[0].latitude").value(10.7947));
    }

    @Test
    void listWithoutACityPassesNull() throws Exception {
        when(cinemaService.list(null)).thenReturn(List.of());
        mockMvc.perform(get("/api/cinemas")).andExpect(status().isOk());
        verify(cinemaService).list(null);
    }

    @Test
    void adminCreatesACinema() throws Exception {
        when(cinemaService.create(any(CinemaRequest.class))).thenReturn(landmark());

        mockMvc.perform(post("/api/v1/cinemas").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("LANDMARK"));
    }

    @Test
    void anInvalidBodyIs400AndNeverReachesTheService() throws Exception {
        mockMvc.perform(post("/api/v1/cinemas").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"code":"x y","name":"","city":"hcm1","address":"a","latitude":123}"""))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(cinemaService);
    }

    @Test
    void aCustomerCannotCreateACinema() throws Exception {
        mockMvc.perform(post("/api/v1/cinemas").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden());
        verifyNoInteractions(cinemaService);
    }
}
