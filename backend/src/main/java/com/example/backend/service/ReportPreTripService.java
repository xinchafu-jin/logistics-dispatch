package com.example.backend.service;

import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.PreTripInspectionsDAO;
import com.example.backend.dao.ReportReadDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dto.respones.ReportPreTripResponse;
import com.example.backend.dto.respones.ReportPreTripResponse.Check;
import com.example.backend.entity.PreTripInspectionsEntity;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.VehiclesEntity;
import com.example.backend.entity.WarehousesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ReportPreTripService {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private final ReportReadDAO reads;
    private final PreTripInspectionsDAO inspections;
    private final RoutesDAO routes;
    private final DriversDAO drivers;
    private final VehiclesDAO vehicles;
    private final WarehousesDAO warehouses;
    private final OrdersDAO orders;
    private final PreTripPhotoStorageService photos;

    public ReportPreTripService(ReportReadDAO reads, PreTripInspectionsDAO inspections,
            RoutesDAO routes, DriversDAO drivers, VehiclesDAO vehicles, WarehousesDAO warehouses,
            OrdersDAO orders, PreTripPhotoStorageService photos) {
        this.reads = reads;
        this.inspections = inspections;
        this.routes = routes;
        this.drivers = drivers;
        this.vehicles = vehicles;
        this.warehouses = warehouses;
        this.orders = orders;
        this.photos = photos;
    }

    public ReportPreTripResponse history(ReportService.Range range, Long warehouseId,
            Long storeId, Long driverId, Set<Long> vehicleIds) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        if (range.getFrom().isAfter(now.toLocalDate()) || (vehicleIds != null && vehicleIds.isEmpty())) {
            return new ReportPreTripResponse(range.getFrom(), range.getTo(), List.of());
        }
        List<PreTripInspectionsEntity> records = reads.inspections(range.getFrom(),
                        range.getTo().isAfter(now.toLocalDate()) ? now.toLocalDate() : range.getTo()).stream()
                .filter(i -> i.getWorkDate() != null && !i.getWorkDate().isAfter(now.toLocalDate()))
                .filter(i -> i.getSubmittedAt() != null && !i.getSubmittedAt().isAfter(now))
                .filter(i -> driverId == null || driverId.equals(i.getDriverId()))
                // Match the vehicle saved on the inspection, not the route's current vehicle after reassignment.
                .filter(i -> vehicleIds == null || vehicleIds.contains(i.getVehicleId()))
                .sorted(Comparator.comparing(PreTripInspectionsEntity::getSubmittedAt)
                        .thenComparing(PreTripInspectionsEntity::getId).reversed()).toList();
        if (records.isEmpty()) return new ReportPreTripResponse(range.getFrom(), range.getTo(), List.of());

        var routeMap = index(routes.findAllById(ids(records, PreTripInspectionsEntity::getRouteId)),
                RoutesEntity::getId);
        var driverMap = index(drivers.findAllById(ids(records, PreTripInspectionsEntity::getDriverId)),
                DriversEntity::getId);
        var vehicleMap = index(vehicles.findAllById(ids(records, PreTripInspectionsEntity::getVehicleId)),
                VehiclesEntity::getId);
        var warehouseMap = index(warehouses.findAllById(routeMap.values().stream()
                        .map(RoutesEntity::getWarehouseId).filter(Objects::nonNull).toList()),
                WarehousesEntity::getId);
        Set<Long> storeRoutes = storeId == null ? null : routeMap.isEmpty() ? Set.of()
                : orders.findByRouteIdIn(List.copyOf(routeMap.keySet()))
                .stream().filter(o -> storeId.equals(o.getStoreId())).map(o -> o.getRouteId())
                .collect(Collectors.toSet());

        var rows = records.stream().filter(i -> {
            var route = routeMap.get(i.getRouteId());
            return (warehouseId == null || (route != null && warehouseId.equals(route.getWarehouseId())))
                    && (storeRoutes == null || storeRoutes.contains(i.getRouteId()));
        }).map(i -> {
            var route = routeMap.get(i.getRouteId());
            var driver = driverMap.get(i.getDriverId());
            var vehicle = vehicleMap.get(i.getVehicleId());
            var warehouse = route == null ? null : warehouseMap.get(route.getWarehouseId());
            var checks = checks(i);
            var abnormal = new ArrayList<String>();
            if (i.getAlcoholMgL() != null && i.getAlcoholMgL().signum() != 0) abnormal.add("酒測");
            checks.stream().filter(c -> Boolean.FALSE.equals(c.normal())).map(Check::label).forEach(abnormal::add);
            return new ReportPreTripResponse.Inspection(i.getId(), i.getWorkDate(), i.getSubmittedAt(),
                    i.getDriverId(), driver == null ? null : driver.getName(),
                    driver == null ? null : driver.getAccount(), i.getVehicleId(),
                    vehicle == null ? null : vehicle.getPlateNumber(), route == null ? null : route.getWarehouseId(),
                    warehouse == null ? null : warehouse.getName(), i.getRouteId(), i.getRouteVersion(),
                    i.getAlcoholMgL(), i.getPassed(), i.getInvalidatedAt(), i.getNote(),
                    present(i.getAlcoholPhoto()), present(i.getFaultPhoto()), checks, List.copyOf(abnormal));
        }).toList();
        return new ReportPreTripResponse(range.getFrom(), range.getTo(), rows);
    }

    /** Called only from the ADMIN-protected report endpoint; filenames are never returned to clients. */
    public Path photo(Long inspectionId, String kind) {
        if (!"alcohol".equals(kind) && !"fault".equals(kind)) {
            throw new IllegalArgumentException("照片種類只能是 alcohol 或 fault");
        }
        var inspection = inspections.findById(inspectionId)
                .orElseThrow(() -> new EntityNotFoundException("找不到這筆檢點紀錄"));
        String file = "alcohol".equals(kind) ? inspection.getAlcoholPhoto() : inspection.getFaultPhoto();
        if (!present(file)) throw new EntityNotFoundException("這筆檢點紀錄沒有附照片");
        return photos.resolve(file);
    }

    private List<Check> checks(PreTripInspectionsEntity i) {
        return List.of(
                new Check("dashcam", "行車紀錄器", "開機且正常錄影", i.getDashcam()),
                new Check("engineOil", "五油", "引擎機油", i.getEngineOil()),
                new Check("brakeFluid", "五油", "煞車油", i.getBrakeFluid()),
                new Check("powerSteeringFluid", "五油", "動力方向盤油", i.getPowerSteeringFluid()),
                new Check("transmissionOil", "五油", "變速箱油", i.getTransmissionOil()),
                new Check("fuel", "五油", "燃油", i.getFuel()),
                new Check("coolant", "三水", "冷卻水", i.getCoolant()),
                new Check("batteryWater", "三水", "電瓶水", i.getBatteryWater()),
                new Check("washerFluid", "三水", "雨刷水", i.getWasherFluid()),
                new Check("tirePressure", "二胎", "胎壓", i.getTirePressure()),
                new Check("tireTread", "二胎", "胎紋", i.getTireTread()),
                new Check("headlights", "四燈", "頭燈", i.getHeadlights()),
                new Check("turnSignals", "四燈", "方向燈", i.getTurnSignals()),
                new Check("brakeLights", "四燈", "煞車燈", i.getBrakeLights()),
                new Check("dashboardLights", "四燈", "儀表板燈（沒有警示燈亮）", i.getDashboardLights()));
    }

    private boolean present(String value) { return value != null && !value.isBlank(); }

    private <T> Set<Long> ids(List<T> rows, Function<T, Long> key) {
        return rows.stream().map(key).filter(Objects::nonNull).collect(Collectors.toSet());
    }

    private <T> Map<Long, T> index(Iterable<T> rows, Function<T, Long> key) {
        Map<Long, T> result = new HashMap<>();
        rows.forEach(row -> result.put(key.apply(row), row));
        return result;
    }
}
