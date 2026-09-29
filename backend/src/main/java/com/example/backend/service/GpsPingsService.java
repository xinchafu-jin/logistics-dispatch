package com.example.backend.service;

import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.GpsPingsDAO;
import com.example.backend.dto.request.GpsPingDTO;
import com.example.backend.dto.respones.GpsPingSavedEvent;
import com.example.backend.entity.GpsPingsEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@Transactional
public class GpsPingsService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final GpsPingsDAO gpsPingsDAO;
    private final DriversDAO driversDAO;
    private final AttendanceService attendanceService;
    // 存好 GPS 後發事件，偏離判斷在 commit 之後才跑（RouteDeviationGpsListener）
    private final ApplicationEventPublisher eventPublisher;
    private final long freshnessMinutes;
    private final long retentionYears;

    public GpsPingsService(
            GpsPingsDAO gpsPingsDAO,
            DriversDAO driversDAO,
            AttendanceService attendanceService,
            ApplicationEventPublisher eventPublisher,
            @Value("${app.gps.freshness-minutes:10}") long freshnessMinutes,
            @Value("${app.gps.retention-years:5}") long retentionYears
    ) {
        if (freshnessMinutes <= 0) {
            throw new IllegalArgumentException("GPS有效分鐘數必須大於 0");
        }
        if (retentionYears <= 0) {
            throw new IllegalArgumentException("GPS保存年數必須大於 0");
        }
        this.gpsPingsDAO = gpsPingsDAO;
        this.driversDAO = driversDAO;
        this.attendanceService = attendanceService;
        this.eventPublisher = eventPublisher;
        this.freshnessMinutes = freshnessMinutes;
        this.retentionYears = retentionYears;
    }

    /**
     * driverId 應由 Controller 從 JWT 的 userId 取得，不接受前端自行指定。
     * timestamp 使用伺服器時間，避免手機時間被修改。
     */
    public GpsPingDTO savePing(Long driverId, GpsPingDTO dto) {
        if (!driversDAO.existsById(driverId)) {
            throw new EntityNotFoundException("找不到司機，ID：" + driverId);
        }
        validateCoordinates(dto);
        if (!attendanceService.isGpsUploadAllowed(driverId)) {
            throw new IllegalArgumentException("目前不是可上傳 GPS 的工作狀態");
        }

        GpsPingsEntity entity = new GpsPingsEntity();
        entity.setDriverId(driverId);
        entity.setLat(dto.getLat());
        entity.setLng(dto.getLng());
        entity.setTimestamp(LocalDateTime.now(TAIPEI));
        GpsPingsEntity saved = gpsPingsDAO.save(entity);
        // 偏離判斷等這個交易 commit 後才跑，出錯也不會讓這次上傳失敗（見 RouteDeviationGpsListener）
        eventPublisher.publishEvent(new GpsPingSavedEvent(
                driverId, saved.getLat(), saved.getLng(), saved.getTimestamp()));
        return toDTO(saved);
    }

    @Transactional(readOnly = true)
    public GpsPingDTO findLatestByDriver(Long driverId) {
        if (!driversDAO.existsById(driverId)) {
            throw new EntityNotFoundException("找不到司機，ID：" + driverId);
        }
        return gpsPingsDAO.findTopByDriverIdOrderByTimestampDesc(driverId)
                .map(this::toDTO)
                .orElseThrow(() -> new EntityNotFoundException("該司機尚無 GPS 紀錄"));
    }

    /**
     * 提供主管即時地圖或 Dispatch／OSRM 使用的有效目前位置。
     * 只有工作中且最後更新未超過設定分鐘數的座標才可使用。
     */
    public GpsPingDTO findCurrentPosition(Long driverId) {
        if (!driversDAO.existsById(driverId)) {
            throw new EntityNotFoundException("找不到司機，ID：" + driverId);
        }
        if (!attendanceService.isGpsUploadAllowed(driverId)) {
            throw new IllegalArgumentException("司機目前不是可使用即時 GPS 的工作狀態");
        }

        GpsPingsEntity latest = gpsPingsDAO.findTopByDriverIdOrderByTimestampDesc(driverId)
                .orElseThrow(() -> new EntityNotFoundException("該司機尚無 GPS 紀錄"));
        ensureFresh(latest, LocalDateTime.now(TAIPEI));
        return toDTO(latest);
    }

    /** 主管車隊地圖只取得工作中且未過期的司機位置。 */
    public List<GpsPingDTO> findLatestFleetPositions() {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        LocalDateTime freshnessCutoff = now.minusMinutes(freshnessMinutes);
        return gpsPingsDAO.findLatestForEachDriverSince(freshnessCutoff).stream()
                .filter(entity -> attendanceService.isGpsUploadAllowed(entity.getDriverId()))
                .map(this::toDTO)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<GpsPingDTO> findHistory(Long driverId, LocalDateTime from, LocalDateTime to) {
        if (from == null || to == null) {
            throw new IllegalArgumentException("GPS 查詢起訖時間不能為空");
        }
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("GPS 查詢開始時間不能晚於結束時間");
        }
        return gpsPingsDAO.findAllByDriverIdAndTimestampBetweenOrderByTimestampAsc(driverId, from, to)
                .stream()
                .map(this::toDTO)
                .toList();
    }

    /** 每天凌晨清除超過保存期限的原始GPS軌跡。 */
    @Scheduled(cron = "0 30 2 * * *", zone = "Asia/Taipei")
    public void deleteExpiredPingsOnSchedule() {
        purgeExpiredPings();
    }

    public long purgeExpiredPings() {
        LocalDateTime cutoff = LocalDateTime.now(TAIPEI).minusYears(retentionYears);
        return gpsPingsDAO.deleteByTimestampBefore(cutoff);
    }

    private void validateCoordinates(GpsPingDTO dto) {
        if (dto == null || dto.getLat() == null || dto.getLng() == null) {
            throw new IllegalArgumentException("經緯度不能為空");
        }
        if (!Double.isFinite(dto.getLat()) || dto.getLat() < -90 || dto.getLat() > 90) {
            throw new IllegalArgumentException("緯度必須介於 -90 到 90");
        }
        if (!Double.isFinite(dto.getLng()) || dto.getLng() < -180 || dto.getLng() > 180) {
            throw new IllegalArgumentException("經度必須介於 -180 到 180");
        }
    }

    private void ensureFresh(GpsPingsEntity entity, LocalDateTime now) {
        if (entity.getTimestamp() == null
                || entity.getTimestamp().isBefore(now.minusMinutes(freshnessMinutes))) {
            throw new IllegalArgumentException(
                    "司機 GPS 已超過 " + freshnessMinutes + " 分鐘未更新"
            );
        }
    }

    private GpsPingDTO toDTO(GpsPingsEntity entity) {
        GpsPingDTO dto = new GpsPingDTO();
        dto.setId(entity.getId());
        dto.setDriverId(entity.getDriverId());
        dto.setLat(entity.getLat());
        dto.setLng(entity.getLng());
        dto.setTimestamp(entity.getTimestamp());
        return dto;
    }
}
