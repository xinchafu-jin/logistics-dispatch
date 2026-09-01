package com.example.backend.controller;

import com.example.backend.dto.request.GpsPingDTO;
import com.example.backend.service.GpsPingsService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/** 物流主管查看車隊即時位置與 GPS 歷史紀錄使用的 API。 */
@RestController
@RequestMapping("/api/fleet")
public class FleetController {

    private final GpsPingsService gpsPingsService;

    public FleetController(GpsPingsService gpsPingsService) {
        this.gpsPingsService = gpsPingsService;
    }

    /** 取得目前工作中且 GPS 未過期的全部司機位置。 */
    @GetMapping("/live")
    public List<GpsPingDTO> findLiveFleet() {
        return gpsPingsService.findLatestFleetPositions();
    }

    /** 取得司機最新一筆 GPS，不檢查是否仍在有效時間內。 */
    @GetMapping("/drivers/{driverId}/gps/latest")
    public GpsPingDTO findLatest(@PathVariable Long driverId) {
        return gpsPingsService.findLatestByDriver(driverId);
    }

    /** 取得司機目前可用的位置，會檢查工作狀態與 GPS 新鮮度。 */
    @GetMapping("/drivers/{driverId}/gps/current")
    public GpsPingDTO findCurrent(@PathVariable Long driverId) {
        return gpsPingsService.findCurrentPosition(driverId);
    }

    /** 查詢司機在指定時間範圍內的 GPS 軌跡。 */
    @GetMapping("/drivers/{driverId}/gps/history")
    public List<GpsPingDTO> findHistory(
            @PathVariable Long driverId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to
    ) {
        return gpsPingsService.findHistory(driverId, from, to);
    }

    /** 手動清除超過保存天數的 GPS 資料，並回傳刪除筆數。 */
    @DeleteMapping("/gps/expired")
    public PurgeResponse purgeExpiredGps() {
        return new PurgeResponse(gpsPingsService.purgeExpiredPings());
    }

    /** 清除 GPS API 的回傳格式。 */
    public record PurgeResponse(long deletedCount) {
    }
}
