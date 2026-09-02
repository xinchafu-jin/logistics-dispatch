package com.example.backend.controller;

import com.example.backend.dto.request.TemplatesRequestDTO;
import com.example.backend.dto.respones.DispatchResponse;
import com.example.backend.dto.respones.TemplatesDTO;
import com.example.backend.service.TemplatesService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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

    @GetMapping("/{id}")
    public ResponseEntity<TemplatesDTO> findById(@PathVariable Long id) {
        return ResponseEntity.ok(templatesService.findById(id));
    }

    /**
     * 建立常配編組。
     *
     * <p>request 的每條路線帶 warehouseId + vehicleId + storeIds，
     * storeIds 的陣列順序就是停靠順序（存成 template_stops.sequence）。</p>
     */
    @PostMapping
    public ResponseEntity<TemplatesDTO> create(@Valid @RequestBody TemplatesRequestDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(templatesService.create(dto));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TemplatesDTO> update(
            @PathVariable Long id,
            @Valid @RequestBody TemplatesRequestDTO dto) {
        return ResponseEntity.ok(templatesService.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        templatesService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 套用編組到指定日期：依編組的車輛與門市，撈當天訂單組成路線。
     *
     * <p>會清掉當天該倉既有的草稿路線並重建（沿用 reassign 的行為）。
     * 產生的路線司機為 null，需由調度員指派後才能發布。</p>
     *
     * <p>編組可跨倉，因此回傳每個有排到路線的倉庫各一包看板資料。</p>
     */
    @PostMapping("/{id}/apply")
    public ResponseEntity<List<DispatchResponse>> apply(
            @PathVariable Long id,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(templatesService.applyToDate(id, date));
    }
}
