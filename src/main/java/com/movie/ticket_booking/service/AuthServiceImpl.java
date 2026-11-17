package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.auth.*;
import com.movie.ticket_booking.entity.PasswordResetToken;
import com.movie.ticket_booking.entity.RefreshToken;
import com.movie.ticket_booking.entity.enums.BookingStatus;
import com.movie.ticket_booking.event.PasswordResetRequestedEvent;
import com.movie.ticket_booking.exception.BusinessException;
import com.movie.ticket_booking.repository.BookingRepository;
import com.movie.ticket_booking.repository.PasswordResetTokenRepository;
import org.springframework.context.ApplicationEventPublisher;
import com.movie.ticket_booking.entity.Role;
import com.movie.ticket_booking.entity.User;
import com.movie.ticket_booking.entity.enums.RoleName;
import com.movie.ticket_booking.repository.RefreshTokenRepository;
import com.movie.ticket_booking.repository.RoleRepository;
import com.movie.ticket_booking.repository.UserRepository;
import com.movie.ticket_booking.security.FirebaseIdentity;
import com.movie.ticket_booking.security.FirebaseTokenVerifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Set;

@Service
@Slf4j
public class AuthServiceImpl implements AuthService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;

    private final PasswordResetTokenRepository resetTokens;
    private final BookingRepository bookings;
    private final BookingLifecycleService bookingLifecycle;
    private final ApplicationEventPublisher events;
    private final FirebaseTokenVerifier firebaseVerifier;

    private final long accessTtlSeconds;
    private final long refreshTtlSeconds;
    private final int maxFailedLogins;
    private final Duration lockDuration;
    private final Duration resetTtl;
    private final Duration resetCooldown;

    public AuthServiceImpl(
            UserRepository userRepository,
            RoleRepository roleRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            PasswordResetTokenRepository resetTokens,
            BookingRepository bookings,
            BookingLifecycleService bookingLifecycle,
            ApplicationEventPublisher events,
            FirebaseTokenVerifier firebaseVerifier,
            @Value("${app.jwt.access-ttl-seconds}") long accessTtlSeconds,
            @Value("${app.jwt.refresh-ttl-seconds}") long refreshTtlSeconds,
            @Value("${app.auth.max-failed-logins:5}") int maxFailedLogins,
            @Value("${app.auth.lock-duration:1m}") Duration lockDuration,
            @Value("${app.auth.reset-ttl:30m}") Duration resetTtl,
            @Value("${app.auth.reset-cooldown:60s}") Duration resetCooldown
    ) {
        this.resetTokens = resetTokens;
        this.bookings = bookings;
        this.bookingLifecycle = bookingLifecycle;
        this.events = events;
        this.firebaseVerifier = firebaseVerifier;
        this.maxFailedLogins = maxFailedLogins;
        this.lockDuration = lockDuration;
        this.resetTtl = resetTtl;
        this.resetCooldown = resetCooldown;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.accessTtlSeconds = accessTtlSeconds;
        this.refreshTtlSeconds = refreshTtlSeconds;
    }

    @Override
    @Transactional
    public AuthUserResponse register(RegisterRequest request) {
        String email = normalizeEmail(request.getEmail());
        if (userRepository.existsByEmail(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Email already exists");
        }
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }

        Role userRole = roleRepository.findByName(RoleName.USER)
                .orElseGet(() -> roleRepository.save(Role.builder().name(RoleName.USER).build()));

        User user = User.builder()
                .username(request.getUsername())
                .email(email)
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .enabled(true)
                .roles(Set.of(userRole))
                .build();

        User created = userRepository.save(user);
        log.info("Registered new user id={} email={}", created.getId(), created.getEmail());
        return toUserResponse(created);
    }

    // The failure counter must survive the 401/423 that is thrown right after it is written.
    @Override
    @Transactional(noRollbackFor = {ResponseStatusException.class, BusinessException.class})
    public AuthResponse login(LoginRequest request) {
        String email = normalizeEmail(request.getEmail());
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        if (!user.isEnabled() || user.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User disabled");
        }
        Instant now = Instant.now();
        if (user.getLockedUntil() != null && user.getLockedUntil().isAfter(now)) {
            throw locked(user.getLockedUntil(), now);
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            userRepository.registerLoginFailure(user.getId(), maxFailedLogins, now.plus(lockDuration));
            Instant lockedUntil = userRepository.findLockedUntil(user.getId()).orElse(null);
            if (lockedUntil != null && lockedUntil.isAfter(now)) {
                throw locked(lockedUntil, now);
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }

        boolean hadFailures = user.getFailedLoginAttempts() > 0 || user.getLockedUntil() != null;
        String accessToken = createAccessToken(user, now);
        RefreshToken refreshToken = createRefreshToken(user, now);
        refreshTokenRepository.save(refreshToken);
        AuthUserResponse userResponse = toUserResponse(user);
        if (hadFailures) {
            userRepository.clearLoginFailures(user.getId());
        }

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken.getToken())
                .expiresInSeconds(accessTtlSeconds)
                .user(userResponse)
                .build();
    }

    private static BusinessException locked(Instant until, Instant now) {
        long wait = Math.max(1, Duration.between(now, until).toSeconds());
        return new BusinessException(HttpStatus.LOCKED, "ACCOUNT_LOCKED",
                "Too many failed logins, try again in " + wait + " seconds", List.of(Map.of("retryAfterSeconds", wait)));
    }

    @Override
    @Transactional
    public AuthResponse loginWithFirebase(FirebaseLoginRequest request) {
        FirebaseIdentity identity;
        try {
            identity = firebaseVerifier.verify(request.getIdToken());
        } catch (JwtException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid Firebase token");
        }

        User user = userRepository.findByFirebaseUid(identity.uid()).orElse(null);
        if (user == null) {
            String email = normalizeEmail(identity.email());
            String phone = identity.phone() == null || identity.phone().isBlank() ? null : identity.phone().trim();
            if (email != null && identity.emailVerified()) {
                // Linking or creating by email is only safe when Firebase says the caller owns that address.
                user = userRepository.findByEmail(email).orElse(null);
                if (user == null) {
                    user = createFirebaseUser(identity, email, phone);
                } else if (user.getFirebaseUid() != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Email belongs to another account");
                } else {
                    user.setFirebaseUid(identity.uid());
                    user = userRepository.save(user);
                }
            } else if (phone != null) {
                // A phone number Firebase has just verified by SMS is as good an identity as a verified email.
                user = userRepository.findByPhoneNumber(phone).orElse(null);
                if (user == null) {
                    user = createFirebaseUser(identity, phoneEmail(phone), phone);
                } else if (user.getFirebaseUid() != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Phone number belongs to another account");
                } else {
                    user.setFirebaseUid(identity.uid());
                    user = userRepository.save(user);
                }
            } else {
                throw new BusinessException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED",
                        "Verify your email address before signing in", List.of());
            }
        }
        if (!user.isEnabled() || user.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User disabled");
        }

        Instant now = Instant.now();
        RefreshToken refreshToken = createRefreshToken(user, now);
        refreshTokenRepository.save(refreshToken);
        return AuthResponse.builder()
                .accessToken(createAccessToken(user, now))
                .refreshToken(refreshToken.getToken())
                .expiresInSeconds(accessTtlSeconds)
                .user(toUserResponse(user))
                .build();
    }

    /** The internal login id of a phone-only customer: stable, unique, and never mailed (User.isDeliverable). */
    private static String phoneEmail(String phone) {
        return "p" + phone.replaceAll("\\D", "") + User.PHONE_EMAIL_DOMAIN;
    }

    private User createFirebaseUser(FirebaseIdentity identity, String email, String phone) {
        Role userRole = roleRepository.findByName(RoleName.USER)
                .orElseGet(() -> roleRepository.save(Role.builder().name(RoleName.USER).build()));
        String base = identity.name() != null && !identity.name().isBlank() ? identity.name().trim()
                : !User.isDeliverable(email) ? "Khách " + phone.substring(Math.max(0, phone.length() - 4))
                : email.substring(0, email.indexOf('@'));
        base = base.length() > 40 ? base.substring(0, 40) : base;
        String username = base;
        while (userRepository.existsByUsername(username)) {
            username = base + "-" + UUID.randomUUID().toString().substring(0, 6);
        }
        User created = userRepository.save(User.builder()
                .username(username)
                .email(email)
                .firebaseUid(identity.uid())
                // A phone already on another account is not attached twice (it is unique).
                .phoneNumber(phone != null && userRepository.findByPhoneNumber(phone).isEmpty() ? phone : null)
                // Never used to log in: Firebase owns the credential. Random so password login cannot match it.
                .passwordHash(passwordEncoder.encode(generateRefreshTokenValue()))
                .enabled(true)
                .roles(Set.of(userRole))
                .build());
        log.info("Created user id={} from Firebase login", created.getId());
        return created;
    }

    @Override
    @Transactional
    public AuthResponse refresh(RefreshTokenRequest request) {
        Instant now = Instant.now();
        RefreshToken current = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

        if (current.getRevokedAt() != null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token revoked");
        }
        if (current.getExpiresAt().isBefore(now)) {
            current.setRevokedAt(now);
            refreshTokenRepository.save(current);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token expired");
        }

        User user = current.getUser();

        current.setRevokedAt(now);
        refreshTokenRepository.save(current);

        String newAccessToken = createAccessToken(user, now);
        RefreshToken nextRefreshToken = createRefreshToken(user, now);
        refreshTokenRepository.save(nextRefreshToken);

        return AuthResponse.builder()
                .accessToken(newAccessToken)
                .refreshToken(nextRefreshToken.getToken())
                .expiresInSeconds(accessTtlSeconds)
                .user(toUserResponse(user))
                .build();
    }

    @Override
    @Transactional
    public void logout(RefreshTokenRequest request) {
        Instant now = Instant.now();
        RefreshToken token = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid refresh token"));

        if (token.getRevokedAt() == null) {
            token.setRevokedAt(now);
            refreshTokenRepository.save(token);
        }
    }

    @Override
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        User user = userRepository.findByEmail(normalizeEmail(request.email())).orElse(null);
        if (user == null || !user.isEnabled() || user.getDeletedAt() != null) {
            log.info("Password reset requested for an unknown or disabled account");
            return; // same answer as for a real account: the caller must not learn which emails exist
        }
        Instant now = Instant.now();
        if (resetTokens.existsByUserIdAndCreatedAtAfter(user.getId(), now.minus(resetCooldown))) {
            return;
        }
        resetTokens.retireAll(user.getId(), now);
        String raw = generateRefreshTokenValue();
        resetTokens.save(PasswordResetToken.builder().user(user).tokenHash(sha256(raw)).expiresAt(now.plus(resetTtl)).build());
        events.publishEvent(new PasswordResetRequestedEvent(user.getId(), raw));
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        Instant now = Instant.now();
        PasswordResetToken token = resetTokens.findByTokenHash(sha256(request.token().trim()))
                .orElseThrow(AuthServiceImpl::invalidResetToken);
        Long userId = token.getUser().getId();
        if (resetTokens.markUsed(token.getId(), now) == 0) {
            throw invalidResetToken();
        }
        User user = userRepository.findById(userId).orElseThrow(AuthServiceImpl::invalidResetToken);
        if (!user.isEnabled() || user.getDeletedAt() != null) {
            throw invalidResetToken();
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        refreshTokenRepository.revokeAllActiveByUserId(userId, now);
        resetTokens.retireAll(userId, now);
        log.info("Password reset completed userId={}", userId);
    }

    @Override
    @Transactional
    public AuthUserResponse updateProfile(String requesterEmail, UpdateProfileRequest request) {
        User user = requireUser(requesterEmail);
        String username = request.username().trim();
        if (!username.equals(user.getUsername())) {
            if (userRepository.existsByUsername(username)) {
                throw new BusinessException(HttpStatus.CONFLICT, "USERNAME_TAKEN", "Username already exists");
            }
            user.setUsername(username);
            userRepository.save(user);
        }
        return toUserResponse(user);
    }

    @Override
    @Transactional
    public void changePassword(String requesterEmail, ChangePasswordRequest request) {
        User user = requireUser(requesterEmail);
        if (!passwordEncoder.matches(request.oldPassword(), user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "WRONG_PASSWORD", "The current password is not correct");
        }
        if (request.oldPassword().equals(request.newPassword())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED", "The new password must differ from the current one");
        }
        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        refreshTokenRepository.revokeAllActiveByUserId(user.getId(), Instant.now());
        log.info("Password changed userId={}", user.getId());
    }

    @Override
    @Transactional
    public void deleteAccount(String requesterEmail, DeleteAccountRequest request) {
        User user = requireUser(requesterEmail);
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "WRONG_PASSWORD", "The password is not correct");
        }
        Instant now = Instant.now();
        if (bookings.existsByUserIdAndStatusAndShowtimeEndTimeAfter(user.getId(), BookingStatus.PAID, now)) {
            throw new BusinessException(HttpStatus.CONFLICT, "HAS_ACTIVE_TICKETS",
                    "Cancel your upcoming tickets before deleting the account");
        }
        // Same transaction: either the holds are released and the account is gone, or nothing changed.
        for (Long bookingId : bookings.findIdsByUserIdAndStatus(user.getId(), BookingStatus.PENDING)) {
            bookingLifecycle.cancel(requesterEmail, bookingId);
        }
        Long id = user.getId();
        user.setUsername("deleted-" + id);
        user.setEmail("deleted-" + id + "@deleted.invalid");
        user.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        user.setEnabled(false);
        user.setDeletedAt(now);
        userRepository.saveAndFlush(user);
        refreshTokenRepository.revokeAllActiveByUserId(id, now);
        resetTokens.retireAll(id, now);
        log.info("Account deleted userId={}", id);
    }

    private User requireUser(String email) {
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized");
        }
        User user = userRepository.findByEmail(normalizeEmail(email))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
        if (!user.isEnabled() || user.getDeletedAt() != null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User disabled");
        }
        return user;
    }

    private static BusinessException invalidResetToken() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_RESET_TOKEN", "The reset link is invalid or has expired");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private String createAccessToken(User user, Instant now) {
        List<String> roles = user.getRoles().stream()
                .map(r -> r.getName().name())
                .sorted()
                .toList();

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("ticket-booking")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(accessTtlSeconds))
                .subject(user.getEmail())
                .claim("uid", user.getId())
                .claim("username", user.getUsername())
                .claim("roles", roles)
                .build();

        JwsHeader jwsHeader = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
    }

    private RefreshToken createRefreshToken(User user, Instant now) {
        return RefreshToken.builder()
                .token(generateRefreshTokenValue())
                .user(user)
                .expiresAt(now.plusSeconds(refreshTtlSeconds))
                .build();
    }

    private String generateRefreshTokenValue() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private AuthUserResponse toUserResponse(User user) {
        List<String> roles = user.getRoles().stream()
                .map(r -> r.getName().name())
                .sorted()
                .toList();

        return AuthUserResponse.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .roles(roles)
                .build();
    }

    private String normalizeEmail(String email) {
        if (email == null) {
            return null;
        }
        return email.trim().toLowerCase();
    }
}
