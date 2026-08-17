package com.example.backend.controller;

import com.example.backend.dto.request.VehiclesDTO;
import com.example.backend.service.VehiclesService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vehicles")
public class VehicleController {

    private final VehiclesService vehiclesService;

    public VehicleController(VehiclesService vehiclesService) {
        this.vehiclesService = vehiclesService;
    }

    // GET /api/vehicles
    @GetMapping
    public ResponseEntity<List<VehiclesDTO>> findAll() {
        return ResponseEntity.ok(vehiclesService.findAll());
    }

    // GET /api/vehicles/1
    @GetMapping("/{id}")
    public ResponseEntity<VehiclesDTO> findById(@PathVariable Long id) {
        return ResponseEntity.ok(vehiclesService.findById(id));
    }

    // POST /api/vehicles
    @PostMapping
    public ResponseEntity<VehiclesDTO> create(@Valid @RequestBody VehiclesDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(vehiclesService.create(dto));
    }

    // PUT /api/vehicles/1
    @PutMapping("/{id}")
    public ResponseEntity<VehiclesDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody VehiclesDTO dto) {
        return ResponseEntity.ok(vehiclesService.update(id, dto));
    }

    // DELETE /api/vehicles/1
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        vehiclesService.delete(id);
        return ResponseEntity.noContent().build();
    }
}