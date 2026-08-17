package com.example.backend.controller;

import com.example.backend.dto.request.OrdersDTO;
import com.example.backend.service.OrdersService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrdersService ordersService;

    public OrderController(OrdersService ordersService) {
        this.ordersService = ordersService;
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
}
