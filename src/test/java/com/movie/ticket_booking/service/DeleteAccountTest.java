package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.auth.DeleteAccountRequest;
import com.movie.ticket_booking.entity.User;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.BookingRepository;
import com.movie.ticket_booking.repository.PasswordResetTokenRepository;
import com.movie.ticket_booking.repository.RefreshTokenRepository;
import com.movie.ticket_booking.repository.RoleRepository;
import com.movie.ticket_booking.repository.UserRepository;
import com.movie.ticket_booking.security.FirebaseIdentity;
import com.movie.ticket_booking.security.FirebaseTokenVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Deleting an account: a password account confirms with its password, a Firebase account with a sign-in made moments ago. */
class DeleteAccountTest {
    private static final String EMAIL = "minh@example.com";

    private UserRepository users;
    private BookingRepository bookings;
    private FirebaseTokenVerifier verifier;
    private BCryptPasswordEncoder encoder;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        bookings = mock(BookingRepository.class);
        verifier = mock(FirebaseTokenVerifier.class);
        encoder = new BCryptPasswordEncoder(4);
        service = new AuthServiceImpl(users, mock(RoleRepository.class), mock(RefreshTokenRepository.class), encoder, mock(JwtEncoder.class),
                mock(PasswordResetTokenRepository.class), bookings, null, null, verifier, 900, 3600, 5,
                Duration.ofMinutes(1), Duration.ofMinutes(30), Duration.ofSeconds(60));
    }

    private User firebaseUser() {
        User u = User.builder().id(7L).username("Minh").email(EMAIL).passwordHash("random").enabled(true)
                .firebaseUid("uid1").phoneNumber("+84912345678").build();
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(u));
        return u;
    }

    private DeleteAccountRequest token() { return new DeleteAccountRequest(null, "id-token"); }

    private void assertReauth(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(BusinessException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(e.getCode()).isEqualTo("REAUTH_REQUIRED");
        });
    }

    @Test
    void aFirebaseAccountWithoutATokenIsAskedToSignInAgain() {
        User u = firebaseUser();
        assertReauth(() -> service.deleteAccount(EMAIL, new DeleteAccountRequest(null, null)));
        assertThat(u.getDeletedAt()).isNull();
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void aSignInThatIsTooOldIsRefused() {
        firebaseUser();
        when(verifier.verify("id-token")).thenReturn(new FirebaseIdentity("uid1", EMAIL, true, null, null, Instant.now().minus(Duration.ofHours(2))));
        assertReauth(() -> service.deleteAccount(EMAIL, token()));
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void aTokenWithNoAuthTimeOrFromAnotherFirebaseAccountOrInvalidIsRefused() {
        firebaseUser();
        when(verifier.verify("id-token")).thenReturn(new FirebaseIdentity("uid1", EMAIL, true, null, null, null));
        assertReauth(() -> service.deleteAccount(EMAIL, token()));

        when(verifier.verify("id-token")).thenReturn(new FirebaseIdentity("someone-else", EMAIL, true, null, null, Instant.now()));
        assertReauth(() -> service.deleteAccount(EMAIL, token()));

        when(verifier.verify("id-token")).thenThrow(new JwtException("bad"));
        assertReauth(() -> service.deleteAccount(EMAIL, token()));
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void aFreshSignInDeletesTheAccountAndFreesTheFirebaseAccountAndPhone() {
        User u = firebaseUser();
        when(verifier.verify("id-token")).thenReturn(new FirebaseIdentity("uid1", EMAIL, true, null, null, Instant.now().minusSeconds(30)));

        service.deleteAccount(EMAIL, token());

        assertThat(u.getDeletedAt()).isNotNull();
        assertThat(u.isEnabled()).isFalse();
        assertThat(u.getEmail()).isEqualTo("deleted-7@deleted.invalid");
        assertThat(u.getFirebaseUid()).isNull();
        assertThat(u.getPhoneNumber()).isNull();
        verify(users).saveAndFlush(u);
    }

    @Test
    void anUpcomingPaidTicketStillBlocksDeletion() {
        firebaseUser();
        when(verifier.verify("id-token")).thenReturn(new FirebaseIdentity("uid1", EMAIL, true, null, null, Instant.now()));
        when(bookings.existsByUserIdAndStatusAndShowtimeEndTimeAfter(any(), any(), any())).thenReturn(true);

        assertThatThrownBy(() -> service.deleteAccount(EMAIL, token())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getCode()).isEqualTo("HAS_ACTIVE_TICKETS"));
        verify(users, never()).saveAndFlush(any());
    }

    @Test
    void anAccountWithAPasswordStillConfirmsWithIt() {
        User u = User.builder().id(9L).username("Old").email(EMAIL).passwordHash(encoder.encode("correct-horse-1")).enabled(true).build();
        when(users.findByEmail(EMAIL)).thenReturn(Optional.of(u));

        assertThatThrownBy(() -> service.deleteAccount(EMAIL, new DeleteAccountRequest("wrong")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("WRONG_PASSWORD"));
        assertThatThrownBy(() -> service.deleteAccount(EMAIL, new DeleteAccountRequest(null, "id-token")))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo("WRONG_PASSWORD"));

        service.deleteAccount(EMAIL, new DeleteAccountRequest("correct-horse-1"));
        assertThat(u.getDeletedAt()).isNotNull();
    }
}
