package com.example.backend.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 管理員查詢及結案配送異常的 API。 */
@RestController
@RequestMapping("/api/exceptions")
public class ExceptionController {

    @GetMapping
    public ResponseEntity<Map<String, Object>> findAll(
            @RequestParam(value = "status", defaultValue = "OPEN") String status) {
        return pending("GET /api/exceptions?status=" + status);
    }

    @PatchMapping("/{id}")
    public ResponseEntity<Map<String, Object>> handle(
            @PathVariable Long id,
            @RequestBody Map<String, Object> request) {
        return pending("PATCH /api/exceptions/" + id);
    }

    private ResponseEntity<Map<String, Object>> pending(String api) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).body(Map.of(
                "success", false,
                "message", "Controller 已建立，尚未接上 Service",
                "api", api
        ));
    }
}
