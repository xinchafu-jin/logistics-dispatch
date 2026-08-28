package com.example.backend.controller;

import com.example.backend.dispatch.CpcFuelPriceClient;
import tools.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fuel-prices")
public class FuelPriceController {

    private final CpcFuelPriceClient cpcFuelPriceClient;

    public FuelPriceController(CpcFuelPriceClient cpcFuelPriceClient) {
        this.cpcFuelPriceClient = cpcFuelPriceClient;
    }

    @GetMapping("/raw")
    public ResponseEntity<JsonNode> getRawPrices() {
        return ResponseEntity.ok(cpcFuelPriceClient.getPrices());
    }
}