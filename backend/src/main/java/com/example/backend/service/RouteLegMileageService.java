package com.example.backend.service;

import com.example.backend.constants.RouteLegLocationType;
import com.example.backend.dao.DeliveryRecordsDAO;
import com.example.backend.dao.MileageLogsDAO;
import com.example.backend.dao.RouteLegMileagesDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.entity.DeliveryRecordsEntity;
import com.example.backend.entity.MileageLogsEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RouteLegMileagesEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.StoresEntity;
import com.example.backend.entity.WarehousesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/** 在抵達每一站與回倉時，將 GPS 軌跡切段並永久保存該段系統里程。 */
@Service
@Transactional
public class RouteLegMileageService {

    private final RouteLegMileagesDAO routeLegMileagesDAO;
    private final MileageLogsDAO mileageLogsDAO;
    private final DeliveryRecordsDAO deliveryRecordsDAO;
    private final RoutesDAO routesDAO;
    private final StoresDAO storesDAO;
    private final WarehousesDAO warehousesDAO;
    private final GpsDistanceService gpsDistanceService;

    public RouteLegMileageService(
            RouteLegMileagesDAO routeLegMileagesDAO,
            MileageLogsDAO mileageLogsDAO,
            DeliveryRecordsDAO deliveryRecordsDAO,
            RoutesDAO routesDAO,
            StoresDAO storesDAO,
            WarehousesDAO warehousesDAO,
            GpsDistanceService gpsDistanceService
    ) {
        this.routeLegMileagesDAO = routeLegMileagesDAO;
        this.mileageLogsDAO = mileageLogsDAO;
        this.deliveryRecordsDAO = deliveryRecordsDAO;
        this.routesDAO = routesDAO;
        this.storesDAO = storesDAO;
        this.warehousesDAO = warehousesDAO;
        this.gpsDistanceService = gpsDistanceService;
    }

    /** 抵達門市時保存「公司／上一站到目前門市」的分段系統里程。 */
    public RouteLegMileagesEntity recordArrival(
            Long driverId,
            OrdersEntity order,
            DeliveryRecordsEntity deliveryRecord,
            LocalDateTime arrivedAt
    ) {
        MileageLogsEntity mileage = mileageLogsDAO.findForUpdate(driverId, arrivedAt.toLocalDate())
                .orElseThrow(() -> new IllegalArgumentException("請先登記出車里程，才能登記抵達門市"));
        validateOpenMileage(mileage, order.getRouteId());

        RoutesEntity route = findRoute(mileage.getRouteId());
        StoresEntity targetStore = findStore(order.getStoreId());
        Optional<RouteLegMileagesEntity> latest = routeLegMileagesDAO
                .findFirstByMileageLogIdOrderBySequenceDesc(mileage.getId());

        LocalDateTime startedAt;
        RouteLegLocationType fromType;
        Long fromStoreId;
        Double fromLat;
        Double fromLng;
        int sequence;

        if (latest.isEmpty()) {
            WarehousesEntity warehouse = findWarehouse(route.getWarehouseId());
            startedAt = mileage.getStartTime();
            fromType = RouteLegLocationType.WAREHOUSE;
            fromStoreId = null;
            fromLat = warehouse.getLat();
            fromLng = warehouse.getLng();
            sequence = 1;
        } else {
            RouteLegMileagesEntity previousLeg = latest.get();
            if (previousLeg.getToType() != RouteLegLocationType.STORE
                    || previousLeg.getToStoreId() == null
                    || previousLeg.getDeliveryRecordId() == null) {
                throw new IllegalStateException("上一個分段不是有效的門市停靠點，無法計算下一段里程");
            }
            DeliveryRecordsEntity previousDelivery = deliveryRecordsDAO
                    .findById(previousLeg.getDeliveryRecordId())
                    .orElseThrow(() -> new IllegalStateException("找不到上一站的交貨紀錄"));
            if (previousDelivery.getHandledAt() == null) {
                throw new IllegalArgumentException("請先完成上一站交貨、無人簽收或貨況處理，再前往下一站");
            }
            StoresEntity previousStore = findStore(previousLeg.getToStoreId());
            startedAt = previousDelivery.getHandledAt();
            fromType = RouteLegLocationType.STORE;
            fromStoreId = previousStore.getId();
            fromLat = previousStore.getLat();
            fromLng = previousStore.getLng();
            sequence = previousLeg.getSequence() + 1;
        }

        if (startedAt == null || startedAt.isAfter(arrivedAt)) {
            throw new IllegalStateException("分段里程的開始時間不正確，無法登記抵達");
        }
        return calculateAndSave(
                mileage, sequence, fromType, fromStoreId,
                RouteLegLocationType.STORE, targetStore.getId(),
                order.getId(), deliveryRecord.getId(), startedAt, arrivedAt,
                fromLat, fromLng, targetStore.getLat(), targetStore.getLng());
    }

    /** 收車時保存「最後門市到公司」的回程分段。 */
    public void recordReturnToWarehouse(MileageLogsEntity mileage, LocalDateTime returnedAt) {
        Optional<RouteLegMileagesEntity> latest = routeLegMileagesDAO
                .findFirstByMileageLogIdOrderBySequenceDesc(mileage.getId());
        if (latest.isEmpty() || latest.get().getToType() == RouteLegLocationType.WAREHOUSE) {
            return;
        }

        RouteLegMileagesEntity previousLeg = latest.get();
        RoutesEntity route = findRoute(mileage.getRouteId());
        WarehousesEntity warehouse = findWarehouse(route.getWarehouseId());
        StoresEntity previousStore = findStore(previousLeg.getToStoreId());
        LocalDateTime startedAt = previousLeg.getEndedAt();
        String forcedStatus = null;

        if (previousLeg.getDeliveryRecordId() != null) {
            DeliveryRecordsEntity previousDelivery = deliveryRecordsDAO
                    .findById(previousLeg.getDeliveryRecordId()).orElse(null);
            if (previousDelivery != null && previousDelivery.getHandledAt() != null) {
                startedAt = previousDelivery.getHandledAt();
            } else {
                forcedStatus = "MISSING_PREVIOUS_STOP_COMPLETION";
            }
        }

        if (startedAt == null || startedAt.isAfter(returnedAt)) {
            forcedStatus = "INVALID_SEGMENT_TIME";
            startedAt = previousLeg.getEndedAt();
        }

        if (forcedStatus != null) {
            saveWithoutDistance(
                    mileage, previousLeg.getSequence() + 1,
                    previousStore.getId(), startedAt, returnedAt, forcedStatus);
            return;
        }

        calculateAndSave(
                mileage, previousLeg.getSequence() + 1,
                RouteLegLocationType.STORE, previousStore.getId(),
                RouteLegLocationType.WAREHOUSE, null,
                null, null, startedAt, returnedAt,
                previousStore.getLat(), previousStore.getLng(),
                warehouse.getLat(), warehouse.getLng());
    }

    private RouteLegMileagesEntity calculateAndSave(
            MileageLogsEntity mileage,
            int sequence,
            RouteLegLocationType fromType,
            Long fromStoreId,
            RouteLegLocationType toType,
            Long toStoreId,
            Long orderId,
            Long deliveryRecordId,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            Double fromLat,
            Double fromLng,
            Double toLat,
            Double toLng
    ) {
        GpsDistanceService.DistanceResult result;
        try {
            result = gpsDistanceService.calculate(
                    mileage.getDriverId(), startedAt, endedAt,
                    fromLat, fromLng, toLat, toLng);
        } catch (RuntimeException exception) {
            result = new GpsDistanceService.DistanceResult(
                    null, 0, 0, "GPS_DISTANCE_CALCULATION_FAILED");
        }

        RouteLegMileagesEntity leg = baseLeg(
                mileage, sequence, fromType, fromStoreId, toType, toStoreId,
                orderId, deliveryRecordId, startedAt, endedAt);
        leg.setSystemDistanceKm(result.getKilometers());
        leg.setGpsPointCount(result.getPointCount());
        leg.setAcceptedSegmentCount(result.getAcceptedSegmentCount());
        leg.setCalculationStatus(result.getStatus());
        return routeLegMileagesDAO.save(leg);
    }

    private void saveWithoutDistance(
            MileageLogsEntity mileage,
            int sequence,
            Long fromStoreId,
            LocalDateTime startedAt,
            LocalDateTime endedAt,
            String status
    ) {
        RouteLegMileagesEntity leg = baseLeg(
                mileage, sequence, RouteLegLocationType.STORE, fromStoreId,
                RouteLegLocationType.WAREHOUSE, null,
                null, null, startedAt, endedAt);
        leg.setGpsPointCount(0);
        leg.setAcceptedSegmentCount(0);
        leg.setCalculationStatus(status);
        routeLegMileagesDAO.save(leg);
    }

    private RouteLegMileagesEntity baseLeg(
            MileageLogsEntity mileage,
            int sequence,
            RouteLegLocationType fromType,
            Long fromStoreId,
            RouteLegLocationType toType,
            Long toStoreId,
            Long orderId,
            Long deliveryRecordId,
            LocalDateTime startedAt,
            LocalDateTime endedAt
    ) {
        RouteLegMileagesEntity leg = new RouteLegMileagesEntity();
        leg.setRouteId(mileage.getRouteId());
        leg.setMileageLogId(mileage.getId());
        leg.setDriverId(mileage.getDriverId());
        leg.setVehicleId(mileage.getVehicleId());
        leg.setSequence(sequence);
        leg.setFromType(fromType);
        leg.setFromStoreId(fromStoreId);
        leg.setToType(toType);
        leg.setToStoreId(toStoreId);
        leg.setOrderId(orderId);
        leg.setDeliveryRecordId(deliveryRecordId);
        leg.setStartedAt(startedAt);
        leg.setEndedAt(endedAt);
        return leg;
    }

    private void validateOpenMileage(MileageLogsEntity mileage, Long routeId) {
        if (mileage.getStartTime() == null || mileage.getEndTime() != null) {
            throw new IllegalArgumentException("目前沒有進行中的出車里程，不能登記抵達門市");
        }
        if (mileage.getRouteId() == null || !mileage.getRouteId().equals(routeId)) {
            throw new IllegalArgumentException("目前出車里程與這張訂單的路線不一致");
        }
        if (mileage.getVehicleId() == null) {
            throw new IllegalStateException("出車里程沒有綁定車輛");
        }
    }

    private RoutesEntity findRoute(Long routeId) {
        return routesDAO.findById(routeId)
                .orElseThrow(() -> new EntityNotFoundException("找不到分段里程的路線，ID：" + routeId));
    }

    private StoresEntity findStore(Long storeId) {
        return storesDAO.findById(storeId)
                .orElseThrow(() -> new EntityNotFoundException("找不到分段里程的門市，ID：" + storeId));
    }

    private WarehousesEntity findWarehouse(Long warehouseId) {
        return warehousesDAO.findById(warehouseId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到分段里程的公司／倉庫，ID：" + warehouseId));
    }
}
