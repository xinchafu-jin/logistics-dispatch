package com.example.backend.controller;

import com.example.backend.dto.respones.TemplatesDTO;
import com.example.backend.service.TemplatesService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/dispatch/templates")
public class TemplatesController {

    private final TemplatesService templatesService;

    public TemplatesController(TemplatesService templatesService) {
        this.templatesService = templatesService;
    }

    // GET /api/dispatch/templates
    @GetMapping
    public ResponseEntity<List<TemplatesDTO>> findAll() {
        return ResponseEntity.ok(templatesService.findAll());
    }
}
