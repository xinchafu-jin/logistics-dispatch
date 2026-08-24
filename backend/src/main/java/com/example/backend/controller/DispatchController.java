package com.example.backend.controller;

import com.example.backend.dto.request.DispatchDTO;
import com.example.backend.dto.respones.DispatchResponse;
import com.example.backend.service.DispatchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/dispatch")
public class DispatchController {

    private final DispatchService dispatchService;

    public DispatchController(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @PostMapping("/optimize")
    public DispatchResponse optimize(@Valid @RequestBody DispatchDTO request) {
        return dispatchService.optimize(
                request.getDate(),
                request.getWarehouseId(),
                request.getVehicleIds()
        );
    }
}
