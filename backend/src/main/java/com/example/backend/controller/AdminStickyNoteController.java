package com.example.backend.controller;

import com.example.backend.dto.request.AdminStickyNoteRequestDTO;
import com.example.backend.dto.respones.AdminStickyNoteResponse;
import com.example.backend.service.AdminStickyNoteService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 每位主管各自獨立的便利貼 API。 */
@RestController
@RequestMapping("/api/admin-sticky-notes")
public class AdminStickyNoteController {
    private final AdminStickyNoteService service;

    public AdminStickyNoteController(AdminStickyNoteService service) {
        this.service = service;
    }

    @GetMapping
    public List<AdminStickyNoteResponse> findMine(@AuthenticationPrincipal Jwt jwt) {
        return service.findMine(adminId(jwt));
    }

    @PostMapping
    public ResponseEntity<AdminStickyNoteResponse> create(
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AdminStickyNoteRequestDTO request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(adminId(jwt), request));
    }

    @PutMapping("/{id}")
    public AdminStickyNoteResponse update(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody AdminStickyNoteRequestDTO request
    ) {
        return service.update(adminId(jwt), id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            @AuthenticationPrincipal Jwt jwt
    ) {
        service.delete(adminId(jwt), id);
        return ResponseEntity.noContent().build();
    }

    private Long adminId(Jwt jwt) {
        Number userId = jwt.getClaim("userId");
        if (userId == null) {
            throw new IllegalArgumentException("JWT 缺少 userId");
        }
        return userId.longValue();
    }
}
