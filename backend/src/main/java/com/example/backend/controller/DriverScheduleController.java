package com.example.backend.controller;

import com.example.backend.dto.request.DriverShiftDTO;
import com.example.backend.dto.request.ScheduleMonthDTO;
import com.example.backend.service.DriverScheduleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;
import java.util.List;

/** 物流主管建立、編輯及發布司機月班表使用的 API。 */
@RestController
@RequestMapping("/api/driver-schedules")
public class DriverScheduleController {

    private final DriverScheduleService driverScheduleService;

    public DriverScheduleController(DriverScheduleService driverScheduleService) {
        this.driverScheduleService = driverScheduleService;
    }

    /** 建立指定月份的草稿班表；重複呼叫同一月份不會重複建立。 */
    @PostMapping("/months")
    public ScheduleMonthDTO generateMonth(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month
    ) {
        return driverScheduleService.generateMonth(month);
    }

    /** 依月份查詢班表主檔。 */
    @GetMapping("/months")
    public ScheduleMonthDTO findMonth(
            @RequestParam @DateTimeFormat(pattern = "yyyy-MM") YearMonth month
    ) {
        return driverScheduleService.findMonth(month);
    }

    /** 查詢某份月班表內的全部司機班次。 */
    @GetMapping("/months/{scheduleMonthId}/shifts")
    public List<DriverShiftDTO> findMonthShifts(@PathVariable Long scheduleMonthId) {
        return driverScheduleService.findMonthShifts(scheduleMonthId);
    }

    /** 編輯草稿班表中的單一班次。 */
    @PutMapping("/shifts/{shiftId}")
    public DriverShiftDTO updateShift(
            @PathVariable Long shiftId,
            @RequestBody DriverShiftDTO dto
    ) {
        return driverScheduleService.updateShift(shiftId, dto);
    }

    /** 批次套用班次規則；任一筆失敗時整批回滾。 */
    @PutMapping("/months/{scheduleMonthId}/shifts/batch")
    public List<DriverShiftDTO> updateShiftsBatch(
            @PathVariable Long scheduleMonthId,
            @RequestBody List<DriverShiftDTO> updates
    ) {
        return driverScheduleService.updateShiftsBatch(scheduleMonthId, updates);
    }

    /** 將草稿建立後才新增或復職的司機補進整月班表。 */
    @PostMapping("/months/{scheduleMonthId}/sync-drivers")
    public List<DriverShiftDTO> syncActiveDrivers(@PathVariable Long scheduleMonthId) {
        return driverScheduleService.syncActiveDrivers(scheduleMonthId);
    }

    /** 將今天或未來的單一班次改為請假。 */
    @PatchMapping("/shifts/{shiftId}/leave")
    public DriverShiftDTO markLeave(
            @PathVariable Long shiftId,
            @Valid @RequestBody LeaveRequest request
    ) {
        return driverScheduleService.markLeave(shiftId, request.getReason(), request.getVersion());
    }

    /** 發布已排完的班表；發布後司機才查得到。 */
    @PostMapping("/months/{scheduleMonthId}/publish")
    public ScheduleMonthDTO publish(@PathVariable Long scheduleMonthId) {
        return driverScheduleService.publish(scheduleMonthId);
    }

    /** 請假原因資料。 */
    public static class LeaveRequest {

        @NotBlank(message = "請假原因不能為空")
        private String reason;

        private Long version;

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public Long getVersion() {
            return version;
        }

        public void setVersion(Long version) {
            this.version = version;
        }
    }
}
