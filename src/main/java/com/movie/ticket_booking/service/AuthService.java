package com.movie.ticket_booking.service;

import com.movie.ticket_booking.dto.auth.*;

public interface AuthService {

    AuthUserResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request);

    AuthResponse refresh(RefreshTokenRequest request);

    void logout(RefreshTokenRequest request);

    /** US-03: always silent about whether the email exists. */
    void forgotPassword(ForgotPasswordRequest request);

    void resetPassword(ResetPasswordRequest request);

    AuthUserResponse updateProfile(String requesterEmail, UpdateProfileRequest request);

    /** US-16: also signs the user out of every device (all refresh tokens revoked). */
    void changePassword(String requesterEmail, ChangePasswordRequest request);

    /** US-17: cancels PENDING bookings, anonymises the account, keeps bookings and tickets for the records. */
    void deleteAccount(String requesterEmail, DeleteAccountRequest request);
}
