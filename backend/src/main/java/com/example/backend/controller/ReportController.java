package com.example.backend.controller;

import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.dto.respones.ReportResponses;
import com.example.backend.service.ReportService;
import com.example.backend.service.ReportPerformanceService;
import com.example.backend.dto.respones.ReportPerformanceResponse;
import com.example.backend.dto.respones.ReportOutcomesResponse;
import com.example.backend.service.ReportOutcomesService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/** Supervisor-only, read-only operational reports. */
@RestController
@RequestMapping("/api/reports")
public class ReportController {
    public enum ReportPeriod { TODAY, YESTERDAY, THIS_WEEK, THIS_MONTH, CUSTOM }

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private final ReportService reportService;
    private final ReportPerformanceService performanceService;
    private final ReportOutcomesService outcomesService;

    public ReportController(ReportService reportService, ReportPerformanceService performanceService,
            ReportOutcomesService outcomesService) {
        this.reportService = reportService;
        this.performanceService = performanceService;
        this.outcomesService = outcomesService;
    }

    @GetMapping("/outcomes")
    public ReportOutcomesResponse outcomes(
            @RequestParam(required = false) ReportPeriod period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(defaultValue = "false") boolean includeDetails) {
        return outcomesService.outcomes(range(period, date, from, to), warehouseId, includeDetails);
    }

    @GetMapping("/performance")
    public ReportPerformanceResponse performance(
            @RequestParam(required = false) ReportPeriod period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long driverId,
            @RequestParam(required = false) Long vehicleId) {
        return performanceService.performance(range(period, date, from, to), warehouseId, driverId, vehicleId);
    }

    @GetMapping("/summary")
    public ReportResponses.Summary summary(
            @RequestParam(required = false) ReportPeriod period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long warehouseId
    ) {
        return reportService.summary(range(period, date, from, to), warehouseId);
    }

    @GetMapping("/attendance")
    public ReportResponses.Attendance attendance(
            @RequestParam(required = false) ReportPeriod period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long driverId
    ) {
        return reportService.attendance(range(period, date, from, to), driverId);
    }

    @GetMapping("/routes")
    public ReportResponses.Routes routes(
            @RequestParam(required = false) ReportPeriod period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long routeId
    ) {
        return reportService.routes(range(period, date, from, to), warehouseId, routeId);
    }

    @GetMapping("/drivers")
    public ReportResponses.Drivers drivers(
            @RequestParam(required = false) ReportPeriod period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long driverId
    ) {
        return reportService.drivers(range(period, date, from, to), driverId);
    }

    @GetMapping("/vehicles")
    public ReportResponses.Vehicles vehicles(
            @RequestParam(required = false) ReportPeriod period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long vehicleId,
            @RequestParam(defaultValue = "50") double lowLoadThresholdPercent
    ) {
        return reportService.vehicles(range(period, date, from, to),
                warehouseId, vehicleId, lowLoadThresholdPercent);
    }

    @GetMapping("/warehouses")
    public ReportResponses.Warehouses warehouses(
            @RequestParam(required = false) ReportPeriod period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long warehouseId
    ) {
        return reportService.warehouses(range(period, date, from, to), warehouseId);
    }

    @GetMapping("/stores")
    public ReportResponses.Stores stores(
            @RequestParam(required = false) ReportPeriod period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long storeId
    ) {
        return reportService.stores(range(period, date, from, to), warehouseId, storeId);
    }

    @GetMapping("/exceptions")
    public ReportResponses.Exceptions exceptions(
            @RequestParam(required = false) ReportPeriod period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) ExceptionType type,
            @RequestParam(required = false) ExceptionStatus status,
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) Long driverId,
            @RequestParam(required = false) Long routeId
    ) {
        return reportService.exceptions(range(period, date, from, to), type, status,
                warehouseId, storeId, driverId, routeId);
    }

    private ReportService.Range range(ReportPeriod period, LocalDate date, LocalDate from, LocalDate to) {
        if (date != null) {
            if (period != null || from != null || to != null) {
                throw new IllegalArgumentException("date 不能與 period 或 from/to 同時使用");
            }
            return new ReportService.Range(date, date);
        }
        if (from != null || to != null) {
            if (period != null && period != ReportPeriod.CUSTOM) {
                throw new IllegalArgumentException("from/to 只能單獨使用或搭配 period=CUSTOM");
            }
            return new ReportService.Range(from, to);
        }
        if (period == ReportPeriod.CUSTOM) {
            throw new IllegalArgumentException("period=CUSTOM 必須提供 from 與 to");
        }
        LocalDate today = LocalDate.now(TAIPEI);
        return switch (period == null ? ReportPeriod.TODAY : period) {
            case TODAY -> new ReportService.Range(today, today);
            case YESTERDAY -> new ReportService.Range(today.minusDays(1), today.minusDays(1));
            case THIS_WEEK -> new ReportService.Range(
                    today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)), today);
            case THIS_MONTH -> new ReportService.Range(today.withDayOfMonth(1), today);
            case CUSTOM -> throw new IllegalArgumentException("period=CUSTOM 必須提供 from 與 to");
        };
    }
}
