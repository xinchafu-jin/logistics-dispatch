package com.example.backend.controller;

import com.example.backend.dispatch.CpcFuelPriceClient;
import com.example.backend.dto.respones.FuelPriceResponse;
import com.example.backend.service.FuelPriceService;
import tools.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/fuel-prices")
public class FuelPriceController {

    private final CpcFuelPriceClient cpcFuelPriceClient;
    private final FuelPriceService fuelPriceService;

    public FuelPriceController(
            CpcFuelPriceClient cpcFuelPriceClient,
            FuelPriceService fuelPriceService
    ) {
        this.cpcFuelPriceClient = cpcFuelPriceClient;
        this.fuelPriceService = fuelPriceService;
    }

    /** 取得目前已生效的最新貨車柴油價格。 */
    @GetMapping({"", "/latest"})
    public FuelPriceResponse latest() {
        return fuelPriceService.latest();
    }

    /** 取得指定日期有效的貨車柴油價格。 */
    @GetMapping("/effective")
    public FuelPriceResponse effective(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return fuelPriceService.effective(date);
    }

    /** 查詢貨車柴油價格歷史，可只提供其中一端日期。 */
    @GetMapping("/history")
    public List<FuelPriceResponse> history(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return fuelPriceService.history(from, to);
    }

    /** 保留中油 OpenData 原始回應，供資料來源除錯。 */
    @GetMapping("/raw")
    public ResponseEntity<JsonNode> getRawPrices() {
        return ResponseEntity.ok(cpcFuelPriceClient.getPrices());
    }
}
