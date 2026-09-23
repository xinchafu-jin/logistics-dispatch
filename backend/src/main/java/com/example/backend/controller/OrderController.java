package com.example.backend.controller;

import com.example.backend.dto.request.OrdersBatchDto;
import com.example.backend.dto.request.OrdersDTO;
import com.example.backend.dto.request.OrderReviewRequestDTO;
import com.example.backend.dto.respones.OrderImportValidationResponse;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.service.OrdersService;
import com.example.backend.service.OrderImportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrdersService ordersService;
    private final OrderImportService orderImportService;

    public OrderController(OrdersService ordersService, OrderImportService orderImportService) {
        this.ordersService = ordersService;
        this.orderImportService = orderImportService;
    }

    @GetMapping
    public ResponseEntity<List<OrdersDTO>> findAll() {
        return ResponseEntity.ok(ordersService.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrdersDTO> findById(@PathVariable Long id) {
        return ResponseEntity.ok(ordersService.findById(id));
    }

    @PostMapping
    public ResponseEntity<OrdersDTO> create(@Valid @RequestBody OrdersDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ordersService.create(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<OrdersDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody OrdersDTO dto) {
        return ResponseEntity.ok(ordersService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        ordersService.delete(id);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/batch")
    public ResponseEntity<List<OrdersDTO>> createAll(@Valid @RequestBody OrdersBatchDto request){
        return ResponseEntity.status(HttpStatus.CREATED).body(ordersService.createAll(request.getOrders()));
    }

    /** 驗證匯入資料，但不寫入資料庫。 */
    @PostMapping("/import/validate")
    public ResponseEntity<OrderImportValidationResponse> validateImport(
            @RequestBody OrdersBatchDto request) {
        return ResponseEntity.ok(orderImportService.validate(request));
    }

    /** 將驗證通過的匯入資料正式寫入。 */
    @PostMapping("/import/confirm")
    public ResponseEntity<List<OrdersDTO>> confirmImport(@RequestBody OrdersBatchDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderImportService.confirm(request));
    }

    /** 執行確認、修改、退回或取消等訂單審核動作。 */
    @PatchMapping("/{id}")
    public ResponseEntity<OrdersDTO> review(
            @PathVariable Long id,
            @Valid @RequestBody OrderReviewRequestDTO request) {
        return ResponseEntity.ok(ordersService.review(id, request));
    }

}
