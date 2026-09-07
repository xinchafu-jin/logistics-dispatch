package com.example.backend.controller;

import com.example.backend.dto.request.GPSRouteDTO;
import com.example.backend.dto.respones.GPSRouteResponse;
import com.example.backend.service.GPSRouteService;
import jakarta.validation.Valid;
import org.springframework.boot.actuate.web.exchanges.HttpExchange;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class GPSRouteController {
    private final GPSRouteService gpsRouteService;

    public GPSRouteController(GPSRouteService gpsRouteService) {
        this.gpsRouteService = gpsRouteService;
    }

    @PostMapping("/route")
    public GPSRouteResponse find(@Valid @RequestBody GPSRouteDTO dto) {
        return gpsRouteService.findRoute(dto);
    }
}
