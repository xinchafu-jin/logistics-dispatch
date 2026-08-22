package com.example.backend.dto.respones;

import java.time.Instant;

public record LoginResponse(
        String accessToken,
        String tokenType,
        Instant expiresAt,
        String role,
        Long userId,
        String account,
        String name
) {
}
