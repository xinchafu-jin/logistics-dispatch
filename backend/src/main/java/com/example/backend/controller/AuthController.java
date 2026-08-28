package com.example.backend.controller;

import com.example.backend.dto.request.AdminPasswordResetDTO;
import com.example.backend.dto.request.AdminPasswordResetVerificationDTO;
import com.example.backend.dto.request.DriverPasswordResetDTO;
import com.example.backend.dto.request.LoginRequest;
import com.example.backend.dto.respones.CurrentUserResponse;
import com.example.backend.dto.respones.LoginResponse;
import com.example.backend.service.AdminUsersService;
import com.example.backend.service.AuthService;
import com.example.backend.service.DriversService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints used by the administrator and driver portals.
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AdminUsersService adminUsersService;
    private final DriversService driversService;

    public AuthController(
            AuthService authService,
            AdminUsersService adminUsersService,
            DriversService driversService
    ) {
        this.authService = authService;
        this.adminUsersService = adminUsersService;
        this.driversService = driversService;
    }

    @PostMapping("/admin/login")
    public LoginResponse loginAdmin(@Valid @RequestBody LoginRequest request) {
        return authService.loginAdmin(request);
    }

    @PostMapping("/driver/login")
    public LoginResponse loginDriver(@Valid @RequestBody LoginRequest request) {
        return authService.loginDriver(request);
    }

    @PostMapping("/admin/forgot-password/verify")
    public ResponseEntity<Void> verifyAdminPasswordReset(
            @Valid @RequestBody AdminPasswordResetVerificationDTO request) {
        adminUsersService.verifyPasswordResetIdentity(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/forgot-password/reset")
    public ResponseEntity<Void> resetAdminPassword(
            @Valid @RequestBody AdminPasswordResetDTO request) {
        adminUsersService.resetForgottenPassword(request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/driver/forgot-password/reset")
    public ResponseEntity<Void> resetDriverPassword(
            @Valid @RequestBody DriverPasswordResetDTO request) {
        driversService.resetForgottenPassword(request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    public CurrentUserResponse currentUser(@AuthenticationPrincipal Jwt jwt) {
        Number userId = jwt.getClaim("userId");
        return new CurrentUserResponse(
                userId.longValue(),
                jwt.getSubject(),
                jwt.getClaimAsString("name"),
                jwt.getClaimAsString("role")
        );
    }
}
