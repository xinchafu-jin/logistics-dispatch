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

/** 在抵達每站和收車時，將 GPS 軌跡切成可對應訂單的實際里程。 */
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

    /** 儲存倉庫／前一站到目前門市的分段里程。沒有啟動里程時不影響原本交貨流程。 */
    public void recordArrival(
            Long driverId,
            OrdersEntity order,
            DeliveryRecordsEntity deliveryRecord,
            LocalDateTime arrivedAt
    ) {
        Optional<MileageLogsEntity> openMileage = mileageLogsDAO.findForUpdate(driverId, arrivedAt.toLocalDate());
        if (openMileage.isEmpty() || openMileage.get().getEndTime() != null) {
            return;
        }
        MileageLogsEntity mileage = openMileage.get();
        if (!order.getRouteId().equals(mileage.getRouteId()) || mileage.getVehicleId() == null
                || mileage.getStartTime() == null) {
            return;
        }

        RoutesEntity route = findRoute(mileage.getRouteId());
        StoresEntity destination = findStore(order.getStoreId());
        Optional<RouteLegMileagesEntity> latest = routeLegMileagesDAO
                .findFirstByMileageLogIdOrderBySequenceDesc(mileage.getId());

        LocalDateTime startedAt = mileage.getStartTime();
        RouteLegLocationType fromType = RouteLegLocationType.WAREHOUSE;
        Long fromStoreId = null;
        double fromLat;
        double fromLng;
        int sequence = 1;

        if (latest.isEmpty()) {
            WarehousesEntity warehouse = findWarehouse(route.getWarehouseId());
            fromLat = warehouse.getLat();
            fromLng = warehouse.getLng();
        } else {
            RouteLegMileagesEntity previous = latest.get();
            if (previous.getToType() != RouteLegLocationType.STORE || previous.getToStoreId() == null) {
                return;
            }
            StoresEntity previousStore = findStore(previous.getToStoreId());
            DeliveryRecordsEntity previousDelivery = previous.getDeliveryRecordId() == null ? null
                    : deliveryRecordsDAO.findById(previous.getDeliveryRecordId()).orElse(null);
            startedAt = previousDelivery != null && previousDelivery.getHandledAt() != null
                    ? previousDelivery.getHandledAt() : previous.getEndedAt();
            if (startedAt == null || startedAt.isAfter(arrivedAt)) {
                return;
            }
            fromType = RouteLegLocationType.STORE;
            fromStoreId = previousStore.getId();
            fromLat = previousStore.getLat();
            fromLng = previousStore.getLng();
            sequence = previous.getSequence() + 1;
        }

        saveLeg(mileage, sequence, fromType, fromStoreId, RouteLegLocationType.STORE,
                destination.getId(), order.getId(), deliveryRecord.getId(), startedAt, arrivedAt,
                fromLat, fromLng, destination.getLat(), destination.getLng());
    }

    /** 收車時儲存最後一站回到所屬倉庫的分段里程。 */
    public void recordReturnToWarehouse(MileageLogsEntity mileage, LocalDateTime returnedAt) {
        if (mileage.getId() == null || mileage.getRouteId() == null || mileage.getVehicleId() == null) {
            return;
        }
        Optional<RouteLegMileagesEntity> latest = routeLegMileagesDAO
                .findFirstByMileageLogIdOrderBySequenceDesc(mileage.getId());
        if (latest.isEmpty() || latest.get().getToType() != RouteLegLocationType.STORE
                || latest.get().getToStoreId() == null) {
            return;
        }
        RouteLegMileagesEntity previous = latest.get();
        StoresEntity store = findStore(previous.getToStoreId());
        RoutesEntity route = findRoute(mileage.getRouteId());
        WarehousesEntity warehouse = findWarehouse(route.getWarehouseId());
        LocalDateTime startedAt = previous.getEndedAt();
        if (previous.getDeliveryRecordId() != null) {
            LocalDateTime handledAt = deliveryRecordsDAO.findById(previous.getDeliveryRecordId())
                    .map(DeliveryRecordsEntity::getHandledAt)
                    .filter(time -> !time.isAfter(returnedAt))
                    .orElse(null);
            if (handledAt != null) {
                startedAt = handledAt;
            }
        }
        if (startedAt == null || startedAt.isAfter(returnedAt)) {
            return;
        }
        saveLeg(mileage, previous.getSequence() + 1, RouteLegLocationType.STORE, store.getId(),
                RouteLegLocationType.WAREHOUSE, null, null, null, startedAt, returnedAt,
                store.getLat(), store.getLng(), warehouse.getLat(), warehouse.getLng());
    }

    private void saveLeg(
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
            double fromLat,
            double fromLng,
            double toLat,
            double toLng
    ) {
        GpsDistanceService.DistanceResult distance;
        try {
            distance = gpsDistanceService.calculate(mileage.getDriverId(), startedAt, endedAt,
                    fromLat, fromLng, toLat, toLng);
        } catch (RuntimeException exception) {
            distance = new GpsDistanceService.DistanceResult(null, 0, 0, "GPS_DISTANCE_CALCULATION_FAILED");
        }
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
        leg.setSystemDistanceKm(distance.getKilometers());
        leg.setGpsPointCount(distance.getPointCount());
        leg.setAcceptedSegmentCount(distance.getAcceptedSegmentCount());
        leg.setCalculationStatus(distance.getStatus());
        routeLegMileagesDAO.save(leg);
    }

    private RoutesEntity findRoute(Long routeId) {
        return routesDAO.findById(routeId)
                .orElseThrow(() -> new EntityNotFoundException("找不到分段里程路線，ID：" + routeId));
    }

    private StoresEntity findStore(Long storeId) {
        return storesDAO.findById(storeId)
                .orElseThrow(() -> new EntityNotFoundException("找不到分段里程門市，ID：" + storeId));
    }

    private WarehousesEntity findWarehouse(Long warehouseId) {
        return warehousesDAO.findById(warehouseId)
                .orElseThrow(() -> new EntityNotFoundException("找不到分段里程倉庫，ID：" + warehouseId));
    }
}
