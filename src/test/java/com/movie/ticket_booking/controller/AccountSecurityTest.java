package com.movie.ticket_booking.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.movie.ticket_booking.service.email.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** US-01, US-03, US-16, US-17 through the real HTTP layer and services (H2). */
@SpringBootTest
@AutoConfigureMockMvc
class AccountSecurityTest {
    private static final String PASSWORD = "password123";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @MockBean private EmailService email;

    private String address;
    private String username;

    @BeforeEach
    void register() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        address = "acct_" + id + "@example.com";
        username = "acct_" + id.substring(0, 20);
        send("/api/auth/register", Map.of("email", address, "username", username, "password", PASSWORD)).andExpect(status().isCreated());
    }

    private ResultActions send(String path, Object body) throws Exception {
        return mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    private ResultActions login(String pw) throws Exception {
        return send("/api/auth/login", Map.of("email", address, "password", pw));
    }

    private JsonNode loginOk() throws Exception {
        return json.readTree(login(PASSWORD).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private String bearer(JsonNode tokens) {
        return "Bearer " + tokens.get("accessToken").asText();
    }

    // ---- US-01: five wrong passwords lock the account for a minute

    @Test
    void fifthWrongPasswordLocksAndEvenTheRightPasswordIsRefusedUntilItExpires() throws Exception {
        for (int i = 0; i < 4; i++) {
            login("wrong-password").andExpect(status().isUnauthorized());
        }
        login("wrong-password").andExpect(status().isLocked())
                .andExpect(jsonPath("$.code").value("ACCOUNT_LOCKED"))
                .andExpect(jsonPath("$.details[0].retryAfterSeconds").isNumber());
        login(PASSWORD).andExpect(status().isLocked());

        jdbc.update("UPDATE users SET locked_until = TIMESTAMPADD(MINUTE, -1, CURRENT_TIMESTAMP) WHERE email = ?", address);
        login(PASSWORD).andExpect(status().isOk());
        assertEquals(0, jdbc.queryForObject("SELECT failed_login_attempts FROM users WHERE email=?", Integer.class, address));
    }

    @Test
    void aSuccessfulLoginClearsTheFailureStreak() throws Exception {
        for (int i = 0; i < 3; i++) login("wrong-password").andExpect(status().isUnauthorized());
        login(PASSWORD).andExpect(status().isOk());
        for (int i = 0; i < 4; i++) login("wrong-password").andExpect(status().isUnauthorized());
        login(PASSWORD).andExpect(status().isOk());
    }

    @Test
    void anUnknownEmailIsJustA401() throws Exception {
        send("/api/auth/login", Map.of("email", "nobody_" + UUID.randomUUID() + "@example.com", "password", "whatever1"))
                .andExpect(status().isUnauthorized());
    }

    // ---- US-03: forgot / reset password

    @Test
    void forgotPasswordAnswersTheSameForUnknownEmailsAndSendsNothing() throws Exception {
        send("/api/v1/auth/forgot-password", Map.of("email", "ghost_" + UUID.randomUUID() + "@example.com")).andExpect(status().isAccepted());
        verify(email, never()).sendPasswordReset(any(), anyString());
    }

    @Test
    void resetFlowWorksOnceSignsEverythingOutAndAllowsTheNewPassword() throws Exception {
        String oldRefresh = loginOk().get("refreshToken").asText();

        send("/api/auth/forgot-password", Map.of("email", address.toUpperCase())).andExpect(status().isAccepted());
        String token = capturedToken();
        send("/api/auth/forgot-password", Map.of("email", address)).andExpect(status().isAccepted()); // inside the cooldown
        verify(email, times(1)).sendPasswordReset(any(), anyString());

        send("/api/auth/reset-password", Map.of("token", token, "newPassword", "brand-new-pass")).andExpect(status().isNoContent());

        login(PASSWORD).andExpect(status().isUnauthorized());
        login("brand-new-pass").andExpect(status().isOk());
        send("/api/auth/refresh", Map.of("refreshToken", oldRefresh)).andExpect(status().isUnauthorized());
        send("/api/auth/reset-password", Map.of("token", token, "newPassword", "another-pass-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));
    }

    @Test
    void garbageExpiredAndShortPasswordResetsAreRefused() throws Exception {
        send("/api/auth/reset-password", Map.of("token", "not-a-real-token", "newPassword", "brand-new-pass"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));

        send("/api/auth/forgot-password", Map.of("email", address)).andExpect(status().isAccepted());
        String token = capturedToken();
        send("/api/auth/reset-password", Map.of("token", token, "newPassword", "short")).andExpect(status().isBadRequest());

        jdbc.update("UPDATE password_reset_token SET expires_at = TIMESTAMPADD(MINUTE, -1, CURRENT_TIMESTAMP)");
        send("/api/auth/reset-password", Map.of("token", token, "newPassword", "brand-new-pass"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_RESET_TOKEN"));
        login(PASSWORD).andExpect(status().isOk());
    }

    @Test
    void theStoredTokenIsAHashNotTheEmailedValue() throws Exception {
        send("/api/auth/forgot-password", Map.of("email", address)).andExpect(status().isAccepted());
        String token = capturedToken();
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_token WHERE token_hash = ?", Integer.class, token));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM password_reset_token t JOIN users u ON u.id=t.user_id WHERE u.email=? AND LENGTH(t.token_hash)=64", Integer.class, address));
    }

    private String capturedToken() {
        ArgumentCaptor<String> link = ArgumentCaptor.forClass(String.class);
        verify(email, atLeastOnce()).sendPasswordReset(any(), link.capture());
        String raw = link.getValue();
        assertTrue(raw.startsWith("ceta://reset-password?token="), raw);
        return URLDecoder.decode(raw.substring(raw.indexOf('=') + 1), StandardCharsets.UTF_8);
    }

    // ---- US-16: change password, profile

    @Test
    void changingThePasswordSignsOutOtherDevicesAndNeedsTheOldOne() throws Exception {
        JsonNode phone = loginOk();
        JsonNode tablet = loginOk();

        mvc.perform(put("/api/v1/auth/me/password").header("Authorization", bearer(phone)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("oldPassword", "not-my-password", "newPassword", "brand-new-pass"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));
        mvc.perform(put("/api/auth/me/password").header("Authorization", bearer(phone)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("oldPassword", PASSWORD, "newPassword", PASSWORD))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_UNCHANGED"));
        mvc.perform(put("/api/auth/me/password").header("Authorization", bearer(phone)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("oldPassword", PASSWORD, "newPassword", "brand-new-pass"))))
                .andExpect(status().isNoContent());

        send("/api/auth/refresh", Map.of("refreshToken", tablet.get("refreshToken").asText())).andExpect(status().isUnauthorized());
        send("/api/auth/refresh", Map.of("refreshToken", phone.get("refreshToken").asText())).andExpect(status().isUnauthorized());
        login("brand-new-pass").andExpect(status().isOk());
    }

    @Test
    void passwordAndProfileEndpointsNeedALogin() throws Exception {
        mvc.perform(put("/api/auth/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"a\",\"newPassword\":\"b1234567\"}")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/auth/me").contentType(MediaType.APPLICATION_JSON).content("{\"username\":\"abc\"}")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/auth/me").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"x\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    void usernameCanBeChangedButNotToSomeoneElses() throws Exception {
        JsonNode me = loginOk();
        mvc.perform(patch("/api/auth/me").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", "renamed_" + username.substring(5, 15)))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.username").value("renamed_" + username.substring(5, 15)));

        String otherName = "other_" + UUID.randomUUID().toString().substring(0, 12);
        send("/api/auth/register", Map.of("email", "o_" + UUID.randomUUID() + "@example.com", "username", otherName, "password", PASSWORD)).andExpect(status().isCreated());
        mvc.perform(patch("/api/auth/me").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("username", otherName))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
        mvc.perform(patch("/api/auth/me").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ab\"}")).andExpect(status().isBadRequest());
    }

    // ---- US-17: delete account

    @Test
    void deletingTheAccountNeedsThePasswordAndEndsTheLogin() throws Exception {
        JsonNode me = loginOk();
        mvc.perform(delete("/api/auth/me").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("password", "not-my-password"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));
        login(PASSWORD).andExpect(status().isOk());

        mvc.perform(delete("/api/v1/auth/me").header("Authorization", bearer(me)).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("password", PASSWORD))))
                .andExpect(status().isNoContent());

        login(PASSWORD).andExpect(status().isUnauthorized());
        send("/api/auth/refresh", Map.of("refreshToken", me.get("refreshToken").asText())).andExpect(status().isUnauthorized());
        // The identity is released: the same email can sign up again, and the old row no longer carries personal data.
        send("/api/auth/register", Map.of("email", address, "username", username, "password", PASSWORD)).andExpect(status().isCreated());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email LIKE 'deleted-%@deleted.invalid' AND deleted_at IS NOT NULL AND enabled = FALSE", Integer.class) > 0);
    }
}
