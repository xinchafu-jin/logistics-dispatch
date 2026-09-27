package com.example.backend.controller;

import com.example.backend.dto.request.PreTripInspectionRequest;
import com.example.backend.service.PreTripInspectionService;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.*;

@RestController
@RequestMapping("/api/driver/pre-trip")
public class PreTripInspectionController {
    private final PreTripInspectionService service;
    public PreTripInspectionController(PreTripInspectionService service) { this.service = service; }
    @GetMapping
    public PreTripInspectionService.Result find(@AuthenticationPrincipal Jwt jwt, @RequestParam Long routeId) {
        return service.find(driverId(jwt), routeId);
    }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PreTripInspectionService.Result submit(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestPart("request") PreTripInspectionRequest request,
            @RequestPart("alcoholPhoto") MultipartFile alcohol, @RequestPart("vehiclePhoto") MultipartFile vehicle,
            @RequestPart("dashcamPhoto") MultipartFile dashcam) {
        return service.submit(driverId(jwt), request, alcohol, vehicle, dashcam);
    }
    @GetMapping("/{id}/photos/{kind}")
    public ResponseEntity<Resource> photo(@AuthenticationPrincipal Jwt jwt, @PathVariable Long id, @PathVariable String kind) throws IOException {
        Path path = service.photo(driverId(jwt), id, kind);
        if (!Files.isRegularFile(path)) return ResponseEntity.notFound().build();
        String name = path.getFileName().toString();
        MediaType type = name.endsWith(".png") ? MediaType.IMAGE_PNG : name.endsWith(".webp")
                ? MediaType.parseMediaType("image/webp") : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore()).body(new FileSystemResource(path));
    }
    private Long driverId(Jwt jwt) {
        Number id = jwt.getClaim("userId");
        if (id == null) throw new IllegalArgumentException("JWT 缺少 userId");
        return id.longValue();
    }
    @ExceptionHandler(org.springframework.web.multipart.support.MissingServletRequestPartException.class)
    public ResponseEntity<com.example.backend.dto.respones.ApiResponse> missingPart() {
        return ResponseEntity.badRequest().body(com.example.backend.dto.respones.ApiResponse.failure("檢查資料與三張照片皆必填"));
    }
}
