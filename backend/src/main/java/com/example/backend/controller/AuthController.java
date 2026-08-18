package com.example.backend.controller;

import com.example.backend.dto.request.LoginRequest;
import com.example.backend.dto.respones.CurrentUserResponse;
import com.example.backend.dto.respones.LoginResponse;
import com.example.backend.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/admin/login")
    public ResponseEntity<LoginResponse> loginAdmin(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.loginAdmin(request));
    }

    @PostMapping("/driver/login")
    public ResponseEntity<LoginResponse> loginDriver(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.loginDriver(request));
    }

    @GetMapping("/me")
    public ResponseEntity<CurrentUserResponse> me(@org.springframework.security.core.annotation.AuthenticationPrincipal Jwt jwt) {
        Number userId = jwt.getClaim("userId");
        return ResponseEntity.ok(new CurrentUserResponse(
                userId.longValue(),
                jwt.getSubject(),
                jwt.getClaimAsString("name"),
                jwt.getClaimAsString("role")
        ));
    }
}
