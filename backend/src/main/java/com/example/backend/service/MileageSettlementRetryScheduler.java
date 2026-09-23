package com.example.backend.service;

import com.example.backend.dao.MileageLogsDAO;
import com.example.backend.entity.MileageLogsEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/** 定期重試已收車但因 GPS 點不足或 OSRM 暫時失敗而尚未結算的里程。 */
@Service
public class MileageSettlementRetryScheduler {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final MileageLogsDAO mileageLogsDAO;
    private final VehicleMileageSettlementService vehicleMileageSettlementService;

    public MileageSettlementRetryScheduler(
            MileageLogsDAO mileageLogsDAO,
            VehicleMileageSettlementService vehicleMileageSettlementService
    ) {
        this.mileageLogsDAO = mileageLogsDAO;
        this.vehicleMileageSettlementService = vehicleMileageSettlementService;
    }

    @Scheduled(cron = "0 */15 * * * *", zone = "Asia/Taipei")
    @Transactional
    public void retryUnsettledMileage() {
        List<Long> ids = mileageLogsDAO.findByMileageSettledAtIsNullAndEndTimeIsNotNull().stream()
                .filter(item -> item.getRouteId() != null && item.getVehicleId() != null)
                .map(MileageLogsEntity::getId)
                .toList();
        for (Long id : ids) {
            retryOne(id);
        }
    }

    public void retryOne(Long id) {
        MileageLogsEntity mileage = mileageLogsDAO.findByIdForUpdate(id).orElse(null);
        if (mileage == null || mileage.getMileageSettledAt() != null
                || mileage.getEndTime() == null) {
            return;
        }
        vehicleMileageSettlementService.settle(mileage, LocalDateTime.now(TAIPEI));
        mileageLogsDAO.save(mileage);
    }
}
