package com.example.backend.controller;

import com.example.backend.dto.respones.GeocodeResponse;
import com.example.backend.service.GeocodeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 地址轉座標 API。 */
@RestController
@RequestMapping("/api/geocode")
public class GeocodeController {

    private final GeocodeService geocodeService;

    public GeocodeController(GeocodeService geocodeService) {
        this.geocodeService = geocodeService;
    }

    @GetMapping
    public GeocodeResponse search(@RequestParam("q") String query) {
        return geocodeService.search(query);
    }
}
