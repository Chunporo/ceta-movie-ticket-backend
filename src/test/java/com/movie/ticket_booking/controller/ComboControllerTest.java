package com.movie.ticket_booking.controller;

import com.movie.ticket_booking.config.SecurityConfig;
import com.movie.ticket_booking.dto.ComboRequest;
import com.movie.ticket_booking.dto.ComboResponse;
import com.movie.ticket_booking.service.ComboService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ComboController.class)
@Import(SecurityConfig.class)
class ComboControllerTest {
    @Autowired private MockMvc mockMvc;
    @MockBean private ComboService comboService;

    private static ComboResponse solo() {
        return new ComboResponse(1L, "SOLO", "Combo Solo", "Solo Combo", "1 bắp + 1 nước", "1 popcorn + 1 drink", null, 79_000L, true, 0);
    }

    private static final String VALID = """
            {"code":"SOLO","name":"Combo Solo","price":79000}""";

    @Test
    void anyoneCanSeeTheMenuWithBothLanguagesAndWholeDongPrices() throws Exception {
        when(comboService.list()).thenReturn(List.of(solo()));

        mockMvc.perform(get("/api/v1/combos").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Combo Solo"))
                .andExpect(jsonPath("$[0].nameEn").value("Solo Combo"))
                .andExpect(jsonPath("$[0].price").value(79000));
    }

    @Test
    void adminCreatesACombo() throws Exception {
        when(comboService.create(any(ComboRequest.class))).thenReturn(solo());
        mockMvc.perform(post("/api/v1/combos").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated());
    }

    @Test
    void zeroOrNegativePricesAndBlankNamesAreRejected() throws Exception {
        for (String body : List.of("{\"code\":\"X\",\"name\":\"n\",\"price\":0}", "{\"code\":\"X\",\"name\":\"n\",\"price\":-5}",
                "{\"code\":\"X\",\"name\":\"\",\"price\":1000}", "{\"code\":\"bad code\",\"name\":\"n\",\"price\":1000}")) {
            mockMvc.perform(post("/api/v1/combos").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(comboService);
    }

    @Test
    void aCustomerCannotCreateACombo() throws Exception {
        mockMvc.perform(post("/api/v1/combos").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_USER")))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden());
        verifyNoInteractions(comboService);
    }
}
