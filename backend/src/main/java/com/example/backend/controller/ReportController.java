package com.example.backend.controller;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/** 管理員查詢單日計畫值與實際值彙總的 API。 */
@RestController
@RequestMapping("/api/reports")
public class ReportController {

    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
                "success", false,
                "message", "Controller 已建立，尚未接上 Service",
                "api", "GET /api/reports/summary?date=" + date
        ));
    }
}
