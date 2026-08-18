package com.example.backend.controller;

import com.example.backend.dto.request.StoreStatusDTO;
import com.example.backend.dto.request.StoresDTO;
import com.example.backend.service.StoresService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/stores")
public class StoreController {

    private final StoresService storesService;

    public StoreController(StoresService storesService) {
        this.storesService = storesService;
    }

    @GetMapping
    public ResponseEntity<List<StoresDTO>> findAll() {
        return ResponseEntity.ok(storesService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<StoresDTO> findById(@PathVariable Long id) {
        return ResponseEntity.ok(storesService.findById(id));
    }

    @PostMapping
    public ResponseEntity<StoresDTO> create(@Valid @RequestBody StoresDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(storesService.create(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<StoresDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody StoresDTO dto) {
        return ResponseEntity.ok(storesService.update(id, dto));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<StoresDTO> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody StoreStatusDTO dto) {

        return ResponseEntity.ok(
                storesService.updateStatus(id, dto.getStatus())
        );
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        storesService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
