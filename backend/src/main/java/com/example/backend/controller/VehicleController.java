package com.example.backend.controller;

import com.example.backend.dto.request.VehicleMileageCorrectionRequestDTO;
import com.example.backend.dto.request.VehiclesDTO;
import com.example.backend.dto.respones.VehicleMileageCorrectionResponse;
import com.example.backend.service.VehiclesService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
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

    // GET /api/vehicles/1/mileage-corrections：這台車的里程更正紀錄，新的在前
    @GetMapping("/{id}/mileage-corrections")
    public ResponseEntity<List<VehicleMileageCorrectionResponse>> mileageCorrections(@PathVariable Long id) {
        return ResponseEntity.ok(vehiclesService.mileageCorrections(id));
    }

    // POST /api/vehicles/1/mileage-corrections：主管更正行車紀錄器里程與保養基準，一定要寫原因
    @PostMapping("/{id}/mileage-corrections")
    public ResponseEntity<VehiclesDTO> correctMileage(
            @AuthenticationPrincipal Jwt jwt,
            @PathVariable Long id,
            @Valid @RequestBody VehicleMileageCorrectionRequestDTO dto) {
        return ResponseEntity.ok(vehiclesService.correctMileage(id, dto, correctedBy(jwt)));
    }

    /** 誰更正的：JWT 裡的顯示名稱，沒有就用帳號（做法同 ExceptionController） */
    private String correctedBy(Jwt jwt) {
        String name = jwt.getClaimAsString("name");
        if (name != null && !name.isBlank()) {
            return name;
        }
        return jwt.getSubject();
    }
}