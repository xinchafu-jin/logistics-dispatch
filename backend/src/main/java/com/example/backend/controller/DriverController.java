package com.example.backend.controller;

import com.example.backend.dto.request.DriverStatusDTO;
import com.example.backend.dto.request.DriversDTO;
import com.example.backend.service.DriversService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/drivers")
public class DriverController {

    private final DriversService driversService;

    public DriverController(DriversService driversService) {
        this.driversService = driversService;
    }

    @GetMapping
    public ResponseEntity<List<DriversDTO>> findAll() {
        return ResponseEntity.ok(driversService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DriversDTO> findById(@PathVariable Long id) {
        return ResponseEntity.ok(driversService.findById(id));
    }

    @PostMapping
    public ResponseEntity<DriversDTO> create(@Valid @RequestBody DriversDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(driversService.create(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<DriversDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody DriversDTO dto) {
        return ResponseEntity.ok(driversService.update(id, dto));
    }

    //包含復職的功能
    @PatchMapping("/{id}/status")
    public ResponseEntity<DriversDTO> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody DriverStatusDTO dto) {

        return ResponseEntity.ok(
                driversService.updateStatus(id, dto.getIsActive())
        );
    }
}
