package com.example.backend.dto.respones;

public record CurrentUserResponse(
        Long userId,
        String account,
        String name,
        String role
) {
}
