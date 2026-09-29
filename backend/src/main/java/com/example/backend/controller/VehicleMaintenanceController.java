package com.example.backend.controller;

import com.example.backend.dto.request.VehicleMaintenanceSettingsDTO;
import com.example.backend.dto.respones.VehicleMaintenanceRecordResponse;
import com.example.backend.service.VehicleMaintenanceService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 主管用的車輛保養與退役 API（只有 ADMIN，見 SecurityConfig）。
 * 送小保、送大保、送維修、改回可用，以及每台車的保養間隔，都是在修改車輛時改（VehicleController）；
 * 這裡只有全車共用的設定、歷史與取消。
 */
@RestController
@RequestMapping("/api/vehicle-maintenance")
public class VehicleMaintenanceController {

    private final VehicleMaintenanceService vehicleMaintenanceService;

    public VehicleMaintenanceController(VehicleMaintenanceService vehicleMaintenanceService) {
        this.vehicleMaintenanceService = vehicleMaintenanceService;
    }

    /** 全車共用的提前提醒公里數 */
    @GetMapping("/settings")
    public VehicleMaintenanceSettingsDTO settings() {
        return vehicleMaintenanceService.settings();
    }

    @PutMapping("/settings")
    public VehicleMaintenanceSettingsDTO saveSettings(@Valid @RequestBody VehicleMaintenanceSettingsDTO settings) {
        return vehicleMaintenanceService.saveSettings(settings);
    }

    /** 這台車的送修歷史，新的在前 */
    @GetMapping("/{vehicleId}/history")
    public List<VehicleMaintenanceRecordResponse> history(@PathVariable Long vehicleId) {
        return vehicleMaintenanceService.history(vehicleId);
    }

    /** 取消進行中的送修：不計次數、不更新基準，車輛改回可用 */
    @PostMapping("/{vehicleId}/cancel")
    public void cancel(@PathVariable Long vehicleId) {
        vehicleMaintenanceService.cancel(vehicleId);
    }
}
