package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.auth.AuthResponse;
import com.movie.ticket_booking.dto.auth.FirebaseLoginRequest;
import com.movie.ticket_booking.entity.Role;
import com.movie.ticket_booking.entity.User;
import com.movie.ticket_booking.entity.enums.RoleName;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.RefreshTokenRepository;
import com.movie.ticket_booking.repository.RoleRepository;
import com.movie.ticket_booking.repository.UserRepository;
import com.movie.ticket_booking.security.FirebaseIdentity;
import com.movie.ticket_booking.security.FirebaseTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FirebaseLoginTest {

    private UserRepository users;
    private RoleRepository roles;
    private FirebaseTokenVerifier verifier;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        roles = mock(RoleRepository.class);
        verifier = mock(FirebaseTokenVerifier.class);
        JwtEncoder encoder = mock(JwtEncoder.class);
        when(encoder.encode(any(JwtEncoderParameters.class))).thenReturn(
                new Jwt("access", Instant.now(), Instant.now().plusSeconds(60), java.util.Map.of("alg", "HS256"), java.util.Map.of("sub", "x")));
        when(roles.findByName(RoleName.USER)).thenReturn(Optional.of(Role.builder().name(RoleName.USER).build()));
        when(users.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            if (u.getId() == null) u.setId(7L);
            return u;
        });
        service = new AuthServiceImpl(users, roles, mock(RefreshTokenRepository.class), new BCryptPasswordEncoder(), encoder,
                null, null, null, null, verifier, 900, 3600, 5, Duration.ofMinutes(1), Duration.ofMinutes(30), Duration.ofSeconds(60));
    }

    private static FirebaseLoginRequest req() {
        FirebaseLoginRequest r = new FirebaseLoginRequest();
        r.setIdToken("t");
        return r;
    }

    @Test
    void newVerifiedUserIsCreatedWithUserRole() {
        when(verifier.verify("t")).thenReturn(new FirebaseIdentity("uid1", "A@Example.com", true, "An"));
        when(users.findByFirebaseUid("uid1")).thenReturn(Optional.empty());
        when(users.findByEmail("a@example.com")).thenReturn(Optional.empty());

        AuthResponse res = service.loginWithFirebase(req());

        assertThat(res.getUser().getEmail()).isEqualTo("a@example.com");
        assertThat(res.getUser().getRoles()).containsExactly("USER");
        assertThat(res.getAccessToken()).isEqualTo("access");
    }

    @Test
    void unverifiedEmailIsRefusedAndNothingIsCreated() {
        when(verifier.verify("t")).thenReturn(new FirebaseIdentity("uid1", "a@example.com", false, null));
        when(users.findByFirebaseUid("uid1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.loginWithFirebase(req()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("Verify your email");
        verify(users, never()).save(any());
    }

    @Test
    void existingLocalUserIsLinkedByVerifiedEmail() {
        User existing = User.builder().id(3L).username("old").email("a@example.com").passwordHash("x").enabled(true)
                .roles(Set.of(Role.builder().name(RoleName.ADMIN).build())).build();
        when(verifier.verify("t")).thenReturn(new FirebaseIdentity("uid1", "a@example.com", true, null));
        when(users.findByFirebaseUid("uid1")).thenReturn(Optional.empty());
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(existing));

        AuthResponse res = service.loginWithFirebase(req());

        assertThat(existing.getFirebaseUid()).isEqualTo("uid1");
        assertThat(res.getUser().getId()).isEqualTo(3L);
        assertThat(res.getUser().getRoles()).containsExactly("ADMIN");
    }

    @Test
    void emailOwnedByAnotherFirebaseAccountIsRefused() {
        User existing = User.builder().id(3L).username("old").email("a@example.com").passwordHash("x").enabled(true)
                .firebaseUid("other").build();
        when(verifier.verify("t")).thenReturn(new FirebaseIdentity("uid1", "a@example.com", true, null));
        when(users.findByFirebaseUid("uid1")).thenReturn(Optional.empty());
        when(users.findByEmail("a@example.com")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.loginWithFirebase(req())).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void invalidTokenIs401() {
        when(verifier.verify("t")).thenThrow(new JwtException("bad"));

        assertThatThrownBy(() -> service.loginWithFirebase(req()))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("401");
    }

    private static FirebaseIdentity phoneOnly(String uid, String phone) {
        return new FirebaseIdentity(uid, null, false, null, phone);
    }

    @Test
    void aPhoneOnlyCustomerIsCreatedWithAnInternalAddressAndTheirNumber() {
        when(verifier.verify("t")).thenReturn(phoneOnly("uid9", "+84912345678"));
        when(users.findByFirebaseUid("uid9")).thenReturn(Optional.empty());
        when(users.findByPhoneNumber("+84912345678")).thenReturn(Optional.empty());

        AuthResponse res = service.loginWithFirebase(req());

        assertThat(res.getUser().getEmail()).isEqualTo("p84912345678@phone.ceta.invalid");
        assertThat(res.getUser().getUsername()).isEqualTo("Khách 5678");
        assertThat(res.getUser().getRoles()).containsExactly("USER");
        org.mockito.ArgumentCaptor<User> saved = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(users).save(saved.capture());
        assertThat(saved.getValue().getPhoneNumber()).isEqualTo("+84912345678");
        assertThat(User.isDeliverable(saved.getValue().getEmail())).as("no mail is ever sent to the placeholder").isFalse();
    }

    @Test
    void anExistingPhoneCustomerIsLinkedToTheNewFirebaseAccount() {
        User existing = User.builder().id(4L).username("old").email("p84912345678@phone.ceta.invalid").passwordHash("x").enabled(true)
                .phoneNumber("+84912345678").roles(Set.of(Role.builder().name(RoleName.USER).build())).build();
        when(verifier.verify("t")).thenReturn(phoneOnly("uid9", "+84912345678"));
        when(users.findByFirebaseUid("uid9")).thenReturn(Optional.empty());
        when(users.findByPhoneNumber("+84912345678")).thenReturn(Optional.of(existing));

        AuthResponse res = service.loginWithFirebase(req());

        assertThat(existing.getFirebaseUid()).isEqualTo("uid9");
        assertThat(res.getUser().getId()).isEqualTo(4L);
    }

    @Test
    void aPhoneOwnedByAnotherFirebaseAccountIsRefused() {
        User existing = User.builder().id(4L).username("old").email("p1@phone.ceta.invalid").passwordHash("x").enabled(true)
                .phoneNumber("+84912345678").firebaseUid("someone-else").build();
        when(verifier.verify("t")).thenReturn(phoneOnly("uid9", "+84912345678"));
        when(users.findByFirebaseUid("uid9")).thenReturn(Optional.empty());
        when(users.findByPhoneNumber("+84912345678")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.loginWithFirebase(req())).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void aVerifiedEmailStillWinsOverThePhoneAndKeepsTheRealAddress() {
        when(verifier.verify("t")).thenReturn(new FirebaseIdentity("uid1", "a@example.com", true, "An", "+84900000001"));
        when(users.findByFirebaseUid("uid1")).thenReturn(Optional.empty());
        when(users.findByEmail("a@example.com")).thenReturn(Optional.empty());
        when(users.findByPhoneNumber("+84900000001")).thenReturn(Optional.empty());

        assertThat(service.loginWithFirebase(req()).getUser().getEmail()).isEqualTo("a@example.com");
    }

    @Test
    void neitherAVerifiedEmailNorAPhoneIsRefused() {
        when(verifier.verify("t")).thenReturn(new FirebaseIdentity("uid1", "a@example.com", false, null, null));
        when(users.findByFirebaseUid("uid1")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.loginWithFirebase(req())).isInstanceOf(BusinessException.class);
    }

    @Test
    void placeholderAddressesAreNotDeliverable() {
        assertThat(User.isDeliverable("a@example.com")).isTrue();
        assertThat(User.isDeliverable("p84912345678@PHONE.CETA.INVALID")).isFalse();
        assertThat(User.isDeliverable(" ")).isFalse();
        assertThat(User.isDeliverable(null)).isFalse();
    }
}
