package com.example.backend.service;

import com.example.backend.constants.FuelType;
import com.example.backend.dao.FuelPriceHistoryDAO;
import com.example.backend.dto.respones.FuelPriceResponse;
import com.example.backend.entity.FuelPriceHistoryEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class FuelPriceService {

    private static final FuelType TRUCK_FUEL_TYPE = FuelType.DIESEL;
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final FuelPriceHistoryDAO fuelPriceHistoryDAO;

    public FuelPriceService(FuelPriceHistoryDAO fuelPriceHistoryDAO) {
        this.fuelPriceHistoryDAO = fuelPriceHistoryDAO;
    }

    /** 取得目前已生效的最新柴油價格。 */
    public FuelPriceResponse latest() {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        return toResponse(findEffectiveAt(now));
    }

    /** 取得指定日期當天最後一筆已生效的柴油價格。 */
    public FuelPriceResponse effective(LocalDate date) {
        if (date == null) {
            throw new IllegalArgumentException("date 為必填");
        }
        return toResponse(findEffectiveAt(date.plusDays(1).atStartOfDay().minusNanos(1)));
    }

    /** 依生效日期查詢柴油價格歷史；未提供日期時不限制該端範圍。 */
    public List<FuelPriceResponse> history(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("from 不可晚於 to");
        }
        LocalDateTime fromTime = from == null ? null : from.atStartOfDay();
        LocalDateTime toTime = to == null ? null : to.plusDays(1).atStartOfDay();
        return fuelPriceHistoryDAO.findHistory(TRUCK_FUEL_TYPE, fromTime, toTime)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    private FuelPriceHistoryEntity findEffectiveAt(LocalDateTime effectiveAt) {
        return fuelPriceHistoryDAO
                .findFirstByFuelTypeAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
                        TRUCK_FUEL_TYPE, effectiveAt)
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到 " + effectiveAt.toLocalDate() + " 以前生效的柴油價格"));
    }

    private FuelPriceResponse toResponse(FuelPriceHistoryEntity entity) {
        return new FuelPriceResponse(
                entity.getId(),
                entity.getFuelType(),
                "超級柴油",
                entity.getPricePerLiter(),
                entity.getEffectiveFrom(),
                entity.getSource(),
                entity.getFetchedAt()
        );
    }
}
