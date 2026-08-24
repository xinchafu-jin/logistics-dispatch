package com.example.backend.controller;

import com.example.backend.dto.request.LoginRequest;
import com.example.backend.dto.respones.CurrentUserResponse;
import com.example.backend.dto.respones.LoginResponse;
import com.example.backend.service.AuthService;
import jakarta.validation.Valid;
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

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/admin/login")
    public LoginResponse loginAdmin(@Valid @RequestBody LoginRequest request) {
        return authService.loginAdmin(request);
    }

    @PostMapping("/driver/login")
    public LoginResponse loginDriver(@Valid @RequestBody LoginRequest request) {
        return authService.loginDriver(request);
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
