package com.example.backend.dto.respones;

import java.time.LocalDateTime;

public record AdminStickyNoteResponse(
        Long id,
        String title,
        String content,
        String color,
        Integer sortOrder,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        Long version
) {
}
