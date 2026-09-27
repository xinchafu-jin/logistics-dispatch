package com.example.backend.controller;

import com.example.backend.dto.request.VehicleMaintenanceRulesDTO;
import com.example.backend.entity.VehicleMaintenanceRecord;
import com.example.backend.service.VehicleMaintenanceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/vehicle-maintenance")
public class VehicleMaintenanceController {
    private final VehicleMaintenanceService service;
    public VehicleMaintenanceController(VehicleMaintenanceService service) { this.service = service; }
    @GetMapping("/rules") public VehicleMaintenanceRulesDTO rules() { return service.rules(); }
    @PutMapping("/rules") public VehicleMaintenanceRulesDTO save(@Valid @RequestBody VehicleMaintenanceRulesDTO dto) { return service.saveRules(dto); }
    @GetMapping("/{vehicleId}/history") public List<VehicleMaintenanceRecord> history(@PathVariable Long vehicleId) { return service.history(vehicleId); }
    @PostMapping("/{vehicleId}/cancel") public void cancel(@PathVariable Long vehicleId) { service.cancel(vehicleId); }
}
