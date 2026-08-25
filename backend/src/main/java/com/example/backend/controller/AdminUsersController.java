package com.example.backend.controller;

import com.example.backend.dto.request.AdminUsersDTO;
import com.example.backend.service.AdminUsersService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin-users")
public class AdminUsersController {

    private final AdminUsersService adminUsersService;

    public AdminUsersController(AdminUsersService adminUsersService) {
        this.adminUsersService = adminUsersService;
    }

    @PostMapping
    public ResponseEntity<AdminUsersDTO> create(@Valid @RequestBody AdminUsersDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(adminUsersService.create(dto));
    }
}
