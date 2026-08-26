package com.example.backend.controller;

import com.example.backend.dto.respones.DispatchResponse;
import com.example.backend.service.DispatchService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/dispatch")
public class DispatchController {

    private final DispatchService dispatchService;

    public DispatchController(DispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @PostMapping("/optimize")
    public ResponseEntity<DispatchResponse> optimize(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam("warehouseId") Long warehouseId,
            @RequestParam("vehicleIds") List<Long> vehicleIds
    ) {
        return ResponseEntity.ok(
                dispatchService.optimize(
                        date,
                        warehouseId,
                        vehicleIds
                )
        );
    }

    /**
     * 調度看板：讀取某天已排定的路線，不會觸發重新排車。
     */
    @GetMapping("/board")
    public ResponseEntity<DispatchResponse> board(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam("warehouseId") Long warehouseId
    ) {
        return ResponseEntity.ok(
                dispatchService.getBoard(date, warehouseId)
        );
    }
}
