package com.example.backend.controller;

import com.example.backend.dto.request.WarehousesDTO;
import com.example.backend.service.WarehousesService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/warehouses")
public class WarehouseController {

    private final WarehousesService warehousesService;

    public WarehouseController(WarehousesService warehousesService) {
        this.warehousesService = warehousesService;
    }

    // GET /api/warehouses
    @GetMapping
    public ResponseEntity<List<WarehousesDTO>> findAll() {
        return ResponseEntity.ok(warehousesService.findAll());
    }

    // GET /api/warehouses/1
    @GetMapping("/{id}")
    public ResponseEntity<WarehousesDTO> findById(@PathVariable Long id) {
        return ResponseEntity.ok(warehousesService.findById(id));
    }

    // POST /api/warehouses
    @PostMapping
    public ResponseEntity<WarehousesDTO> create(
            @Valid @RequestBody WarehousesDTO dto) {

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(warehousesService.create(dto));
    }

    // PUT /api/warehouses/1
    @PutMapping("/{id}")
    public ResponseEntity<WarehousesDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody WarehousesDTO dto) {

        return ResponseEntity.ok(warehousesService.update(id, dto));
    }

    // DELETE /api/warehouses/1
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        warehousesService.delete(id);
        return ResponseEntity.noContent().build();
    }
}