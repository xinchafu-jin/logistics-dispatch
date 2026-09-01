package com.example.backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 地址轉座標 API；後續由 Service 串接 Nominatim。 */
@RestController
@RequestMapping("/api/geocode")
public class GeocodeController {

    @GetMapping
    public ResponseEntity<Map<String, Object>> search(@RequestParam("q") String query) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
                "success", false,
                "message", "Controller 已建立，尚未接上 Nominatim Service",
                "api", "GET /api/geocode?q=" + query
        ));
    }
}
