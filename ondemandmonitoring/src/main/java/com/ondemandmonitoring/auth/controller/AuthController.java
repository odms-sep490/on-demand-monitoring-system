package com.ondemandmonitoring.auth.controller;

import com.ondemandmonitoring.auth.dto.request.FirstLoginPasswordChangeRequest;
import com.ondemandmonitoring.auth.dto.request.ForgotPasswordRequest;
import com.ondemandmonitoring.auth.dto.request.LinkLocalIdentityRequest;
import com.ondemandmonitoring.auth.dto.request.LoginRequest;
import com.ondemandmonitoring.auth.dto.request.RegisterRequest;
import com.ondemandmonitoring.auth.dto.request.ResendOtpRequest;
import com.ondemandmonitoring.auth.dto.request.ResetPasswordRequest;
import com.ondemandmonitoring.auth.dto.request.SocialSyncRequest;
import com.ondemandmonitoring.auth.dto.request.VerifyOtpRequest;
import com.ondemandmonitoring.auth.dto.response.AuthResponse;
import com.ondemandmonitoring.auth.dto.response.RegisterResponse;
import com.ondemandmonitoring.auth.service.IAuthService;
import com.ondemandmonitoring.common.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import lombok.experimental.FieldDefaults;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication", description = "APIs for user authentication, registration, OTP, and session management")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE, makeFinal = true)
public class AuthController {

    IAuthService authService;

    @Operation(summary = "Register user account", description = "Registers a new user account and sends verification OTP")
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResponse>> register(
            @Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created("Registration successful.", authService.register(request)));
    }

    @Operation(summary = "Verify registration OTP", description = "Verifies the email OTP code for newly registered account")
    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<Void>> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        authService.verifyOtp(request);
        return ResponseEntity.ok(ApiResponse.ok("Account verified successfully.", null));
    }

    @Operation(summary = "Resend verification OTP", description = "Resends an OTP verification code via email")
    @PostMapping("/resend-otp")
    public ResponseEntity<ApiResponse<Void>> resendOtp(@Valid @RequestBody ResendOtpRequest request) {
        authService.resendOtp(request);
        return ResponseEntity.ok(ApiResponse.ok("OTP code has been resent via email.", null));
    }

    @Operation(summary = "User login", description = "Authenticates user credentials and issues access & refresh tokens")
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<AuthResponse>> login(
            @Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        return ResponseEntity.ok(ApiResponse.ok("Login successful.", authService.login(request, response)));
    }

    @Operation(summary = "Complete first login password change", description = "Allows newly invited users to change temporary password on first login")
    @PostMapping("/first-login/change-password")
    public ResponseEntity<ApiResponse<AuthResponse>> completeFirstLogin(
            @Valid @RequestBody FirstLoginPasswordChangeRequest request,
            HttpServletResponse response) {
        return ResponseEntity.ok(ApiResponse.ok("Password changed successfully.",
                authService.completeFirstLogin(request, response)));
    }

    @Operation(summary = "Social login sync", description = "Synchronizes social OAuth identity with backend account")
    @PostMapping("/social/sync")
    public ResponseEntity<ApiResponse<AuthResponse>> socialSync(
            @Valid @RequestBody SocialSyncRequest request, HttpServletResponse response) {
        return ResponseEntity.ok(ApiResponse.ok("Social login successful.",
                authService.socialSync(request, response)));
    }

    @Operation(summary = "Link local identity", description = "Links local username/password credentials to a social account")
    @PostMapping("/social/link-local")
    public ResponseEntity<ApiResponse<Void>> linkLocalIdentity(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody LinkLocalIdentityRequest request) {
        String cognitoUsername = jwt.getClaimAsString("username");
        if (cognitoUsername == null || cognitoUsername.isBlank()) {
            cognitoUsername = jwt.getSubject();
        }
        authService.linkLocalIdentity(
                jwt.getSubject(),
                cognitoUsername,
                request);
        return ResponseEntity.ok(ApiResponse.ok("Local login linked successfully.", null));
    }

    @Operation(summary = "Refresh access token", description = "Refreshes expired access token using cookie or refresh token header")
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<AuthResponse>> refresh(HttpServletRequest request) {
        return ResponseEntity.ok(ApiResponse.ok("Token refreshed.", authService.refresh(request)));
    }

    @Operation(summary = "User logout", description = "Logs out current user session and clears authentication cookies")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorizationHeader,
            HttpServletRequest request,
            HttpServletResponse response) {
        String accessToken = authorizationHeader != null && authorizationHeader.startsWith("Bearer ")
                ? authorizationHeader.substring(7)
                : authorizationHeader;
        authService.logout(accessToken, request, response);
        return ResponseEntity.ok(ApiResponse.ok("Logout successful.", null));
    }

    @Operation(summary = "Get CSRF token", description = "Generates a CSRF token for web clients")
    @GetMapping("/csrf")
    public ResponseEntity<ApiResponse<String>> csrf(CsrfToken token) {
        return ResponseEntity.ok(ApiResponse.ok("CSRF token generated.", token.getToken()));
    }

    @Operation(summary = "Request forgot password OTP", description = "Sends a password reset OTP code to user's email")
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponse<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ResponseEntity.ok(ApiResponse.ok("Password reset OTP has been sent via email.", null));
    }

    @Operation(summary = "Reset password", description = "Resets user password using valid OTP code")
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ResponseEntity.ok(ApiResponse.ok("Password reset successful.", null));
    }
}
