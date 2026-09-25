package com.example.backend.service;

import com.example.backend.dao.MileageLogsDAO;
import com.example.backend.dao.GpsPingsDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dispatch.OsrmClient;
import com.example.backend.dispatch.OsrmRouteResponse;
import com.example.backend.dto.respones.FuelPriceResponse;
import com.example.backend.dto.respones.RouteMetricsResponse;
import com.example.backend.entity.MileageLogsEntity;
import com.example.backend.entity.GpsPingsEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import com.example.backend.entity.StoresEntity;
import com.example.backend.entity.VehiclesEntity;
import com.example.backend.entity.WarehousesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 計算含回倉庫的 OSRM 路線時間、里程及油耗預估。 */
@Service
public class RoutePlanMetricsService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final RoutesDAO routesDAO;
    private final OrdersDAO ordersDAO;
    private final WarehousesDAO warehousesDAO;
    private final StoresDAO storesDAO;
    private final VehiclesDAO vehiclesDAO;
    private final MileageLogsDAO mileageLogsDAO;
    private final OsrmClient osrmClient;
    private final FuelPriceService fuelPriceService;
    private final GpsDistanceService gpsDistanceService;
    private final GpsPingsDAO gpsPingsDAO;
    private final int gpsFreshnessMinutes;

    public RoutePlanMetricsService(
            RoutesDAO routesDAO,
            OrdersDAO ordersDAO,
            WarehousesDAO warehousesDAO,
            StoresDAO storesDAO,
            VehiclesDAO vehiclesDAO,
            MileageLogsDAO mileageLogsDAO,
            OsrmClient osrmClient,
            FuelPriceService fuelPriceService,
            GpsDistanceService gpsDistanceService,
            GpsPingsDAO gpsPingsDAO,
            @org.springframework.beans.factory.annotation.Value(
                    "${app.gps.freshness-minutes:10}") int gpsFreshnessMinutes
    ) {
        this.routesDAO = routesDAO;
        this.ordersDAO = ordersDAO;
        this.warehousesDAO = warehousesDAO;
        this.storesDAO = storesDAO;
        this.vehiclesDAO = vehiclesDAO;
        this.mileageLogsDAO = mileageLogsDAO;
        this.osrmClient = osrmClient;
        this.fuelPriceService = fuelPriceService;
        this.gpsDistanceService = gpsDistanceService;
        this.gpsPingsDAO = gpsPingsDAO;
        this.gpsFreshnessMinutes = gpsFreshnessMinutes;
    }

    /** 發布前保存現有 routes 欄位能承載的總里程、總工時與預估油費。 */
    @Transactional
    public void calculateAndStore(LocalDate date) {
        List<RoutesEntity> routes = routesDAO.findByDate(date);
        for (RoutesEntity route : routes) {
            boolean hasActiveOrders = ordersDAO.findByRouteIdOrderBySequence(route.getId()).stream()
                    .anyMatch(order -> order.getStatus().isActive());
            if (!hasActiveOrders) {
                continue;
            }
            PlanCalculation calculation = calculatePlan(route, true);
            route.setTotalDistance(calculation.totalMeters);
            route.setEstimatedWorkMinutes(calculation.totalMinutes);
            route.setEstimatedFuelCost(calculation.plannedFuelCost);
        }
        routesDAO.saveAll(routes);
    }

    @Transactional(readOnly = true)
    public RouteMetricsResponse getMetrics(Long routeId) {
        RoutesEntity route = routesDAO.findById(routeId)
                .orElseThrow(() -> new EntityNotFoundException("找不到路線，ID：" + routeId));
        boolean hasActiveOrders = ordersDAO.findByRouteIdOrderBySequence(route.getId()).stream()
                .anyMatch(order -> order.getStatus().isActive());
        PlanCalculation plan = calculatePlan(route, hasActiveOrders);

        RouteMetricsResponse response = new RouteMetricsResponse();
        response.setRouteId(route.getId());
        response.setDate(route.getDate());
        response.setDriverId(route.getDriverId());
        response.setVehicleId(route.getVehicleId());
        response.setPlannedKm(plan.totalMeters / 1000.0);
        response.setPlannedDriveMinutes(plan.driveMinutes);
        response.setPlannedTotalMinutes(plan.totalMinutes);
        response.setPlannedFuelLiters(plan.plannedFuelLiters);
        response.setPlannedFuelCost(plan.plannedFuelCost);
        response.setPricePerLiter(plan.pricePerLiter);
        response.setFuelStatus(plan.fuelStatus);
        response.setLegs(plan.legs);
        populateLiveEta(response, route);

        List<MileageLogsEntity> mileageSegments = new ArrayList<>(
                mileageLogsDAO.findAllByRouteIdOrderByStartTimeAsc(route.getId()));
        if (mileageSegments.isEmpty() && route.getDriverId() != null) {
            mileageLogsDAO.findByDriverIdAndDate(route.getDriverId(), route.getDate())
                    .ifPresent(mileageSegments::add);
        }
        if (mileageSegments.isEmpty()) {
            response.setMileageStatus("NO_TRIP_BOUNDARY");
            return response;
        }
        WarehousesEntity warehouse = warehousesDAO.findById(route.getWarehouseId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到路線倉庫，ID：" + route.getWarehouseId()));
        validateCoordinates(warehouse.getLat(), warehouse.getLng(),
                "倉庫 " + warehouse.getName());

        double totalKm = 0;
        boolean hasDistance = false;
        boolean allComplete = true;
        String segmentProblem = null;
        for (MileageLogsEntity mileage : mileageSegments) {
            if (mileage.getStartTime() == null) {
                allComplete = false;
                segmentProblem = "MISSING_TRIP_BOUNDARY";
                continue;
            }

            Double segmentKm = mileage.getMileageSettledAt() != null
                    ? mileage.getGpsDistanceKm() : null;
            if (segmentKm == null) {
                LocalDateTime end = mileage.getEndTime();
                if (end == null) {
                    allComplete = false;
                    end = route.getDate().equals(LocalDate.now(TAIPEI))
                            ? LocalDateTime.now(TAIPEI)
                            : route.getDate().atTime(LocalTime.MAX);
                }
                GpsDistanceService.DistanceResult gps = gpsDistanceService.calculate(
                        mileage.getDriverId(), mileage.getStartTime(), end,
                        warehouse.getLat(), warehouse.getLng(),
                        mileage.getEndTime() == null ? null : warehouse.getLat(),
                        mileage.getEndTime() == null ? null : warehouse.getLng());
                segmentKm = gps.getKilometers();
                if (segmentKm == null) {
                    allComplete = false;
                    segmentProblem = gps.getStatus();
                    continue;
                }
            }
            totalKm += segmentKm;
            hasDistance = true;
            if (mileage.getEndTime() == null) {
                allComplete = false;
            }
        }

        if (!hasDistance) {
            response.setMileageStatus(segmentProblem == null
                    ? "NO_GPS_DISTANCE" : segmentProblem);
            return response;
        }
        response.setGpsEstimatedKm(totalKm);
        if (mileageSegments.size() > 1) {
            response.setMileageStatus(allComplete
                    ? "COMPLETE_ROUTE_HANDOVER_SUMMED"
                    : "IN_PROGRESS_ROUTE_HANDOVER_SUMMED");
        } else {
            response.setMileageStatus(allComplete ? "COMPLETE" : "IN_PROGRESS");
        }
        if (plan.kmPerLiter != null) {
            double liters = totalKm / plan.kmPerLiter;
            response.setGpsEstimatedFuelLiters(liters);
            if (plan.pricePerLiter != null) {
                response.setGpsEstimatedFuelCost(liters * plan.pricePerLiter);
            }
        }
        return response;
    }

    private PlanCalculation calculatePlan(RoutesEntity route, boolean activeOnly) {
        WarehousesEntity warehouse = warehousesDAO.findById(route.getWarehouseId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到路線倉庫，ID：" + route.getWarehouseId()));
        validateCoordinates(warehouse.getLat(), warehouse.getLng(), "倉庫 " + warehouse.getName());

        List<OrdersEntity> orders = ordersDAO.findByRouteIdOrderBySequence(route.getId());
        if (activeOnly) {
            orders = orders.stream()
                    .filter(order -> order.getStatus().isActive())
                    .toList();
        }
        if (orders.isEmpty()) {
            throw new IllegalArgumentException("路線 " + route.getId() + " 沒有訂單");
        }
        Map<Long, OrdersEntity> firstOrderByStore = new LinkedHashMap<>();
        for (OrdersEntity order : orders) {
            firstOrderByStore.putIfAbsent(order.getStoreId(), order);
        }
        Map<Long, StoresEntity> stores = new LinkedHashMap<>();
        storesDAO.findAllById(firstOrderByStore.keySet())
                .forEach(store -> stores.put(store.getId(), store));

        VehiclesEntity vehicle = vehiclesDAO.findById(route.getVehicleId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到路線車輛，ID：" + route.getVehicleId()));
        Double kmPerLiter = vehicle.getFuelConsumption();
        if (kmPerLiter != null && kmPerLiter <= 0) {
            throw new IllegalArgumentException("車輛平均油耗必須大於 0 km/L");
        }

        FuelPriceResponse fuelPrice = null;
        try {
            fuelPrice = fuelPriceService.effective(route.getDate());
        } catch (RuntimeException ignored) {
            // 油價不足不能用 0 假裝；里程與時間仍可計算，回應會標示缺少油價。
        }
        Double pricePerLiter = fuelPrice == null
                ? null : fuelPrice.getPricePerLiter().doubleValue();

        List<Location> locations = new ArrayList<>();
        locations.add(new Location(warehouse.getName(), warehouse.getLat(), warehouse.getLng(), null, null));
        for (Map.Entry<Long, OrdersEntity> entry : firstOrderByStore.entrySet()) {
            StoresEntity store = stores.get(entry.getKey());
            if (store == null) {
                throw new EntityNotFoundException("找不到門市，ID：" + entry.getKey());
            }
            validateCoordinates(store.getLat(), store.getLng(), "門市 " + store.getName());
            locations.add(new Location(store.getName(), store.getLat(), store.getLng(),
                    entry.getValue().getId(), store.getId()));
        }
        locations.add(new Location(warehouse.getName(), warehouse.getLat(), warehouse.getLng(), null, null));

        double totalMeters = 0;
        double totalSeconds = 0;
        List<RouteMetricsResponse.Leg> legs = new ArrayList<>();
        for (int index = 1; index < locations.size(); index++) {
            Location from = locations.get(index - 1);
            Location to = locations.get(index);
            OsrmRouteResponse.Route road = osrmClient.route(
                    new double[]{from.lng, from.lat}, new double[]{to.lng, to.lat});
            totalMeters += road.getDistance();
            totalSeconds += road.getDuration();

            RouteMetricsResponse.Leg leg = new RouteMetricsResponse.Leg();
            leg.setSequence(index);
            leg.setFromName(from.name);
            leg.setToName(to.name);
            leg.setOrderId(to.orderId);
            leg.setStoreId(to.storeId);
            leg.setDistanceKm(road.getDistance() / 1000.0);
            leg.setDriveMinutes(road.getDuration() / 60.0);
            if (kmPerLiter != null) {
                double liters = road.getDistance() / 1000.0 / kmPerLiter;
                leg.setEstimatedFuelLiters(liters);
                if (pricePerLiter != null) {
                    leg.setEstimatedFuelCost(liters * pricePerLiter);
                }
            }
            legs.add(leg);
        }

        int driveMinutes = (int) Math.ceil(totalSeconds / 60.0);
        Double plannedFuelLiters = kmPerLiter == null ? null : totalMeters / 1000.0 / kmPerLiter;
        Double plannedFuelCost = plannedFuelLiters == null || pricePerLiter == null
                ? null : plannedFuelLiters * pricePerLiter;
        String fuelStatus = kmPerLiter == null ? "MISSING_VEHICLE_FUEL_CONSUMPTION"
                : pricePerLiter == null ? "MISSING_FUEL_PRICE" : "COMPLETE";
        return new PlanCalculation(totalMeters, driveMinutes, driveMinutes,
                kmPerLiter, pricePerLiter,
                plannedFuelLiters, plannedFuelCost, fuelStatus, legs);
    }

    private void populateLiveEta(RouteMetricsResponse response, RoutesEntity route) {
        if (route.getStatus() != com.example.backend.constants.RouteStatus.PUBLISHED) {
            response.setLiveEtaStatus("ROUTE_NOT_PUBLISHED");
            return;
        }
        if (route.getDriverId() == null) {
            response.setLiveEtaStatus("NO_DRIVER");
            return;
        }
        GpsPingsEntity latest = gpsPingsDAO.findTopByDriverIdOrderByTimestampDesc(route.getDriverId())
                .orElse(null);
        if (latest == null) {
            response.setLiveEtaStatus("NO_GPS");
            return;
        }
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        response.setGpsTimestamp(latest.getTimestamp());
        if (latest.getTimestamp().isAfter(now)
                || Duration.between(latest.getTimestamp(), now).toMinutes() > gpsFreshnessMinutes) {
            response.setLiveEtaStatus("STALE_GPS");
            return;
        }

        WarehousesEntity warehouse = warehousesDAO.findById(route.getWarehouseId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到路線倉庫，ID：" + route.getWarehouseId()));
        List<OrdersEntity> remaining = ordersDAO.findByRouteIdOrderBySequence(route.getId()).stream()
                .filter(order -> order.getStatus().isActive())
                .toList();
        Map<Long, OrdersEntity> firstOrderByStore = new LinkedHashMap<>();
        for (OrdersEntity order : remaining) {
            firstOrderByStore.putIfAbsent(order.getStoreId(), order);
        }
        Map<Long, StoresEntity> stores = new LinkedHashMap<>();
        storesDAO.findAllById(firstOrderByStore.keySet())
                .forEach(store -> stores.put(store.getId(), store));

        double fromLat = latest.getLat();
        double fromLng = latest.getLng();
        double totalMeters = 0;
        double totalSeconds = 0;
        boolean firstLeg = true;
        for (Map.Entry<Long, OrdersEntity> entry : firstOrderByStore.entrySet()) {
            StoresEntity store = stores.get(entry.getKey());
            if (store == null) {
                throw new EntityNotFoundException("找不到門市，ID：" + entry.getKey());
            }
            OsrmRouteResponse.Route road = osrmClient.route(
                    new double[]{fromLng, fromLat}, new double[]{store.getLng(), store.getLat()});
            totalMeters += road.getDistance();
            totalSeconds += road.getDuration();
            if (firstLeg) {
                response.setNextOrderId(entry.getValue().getId());
                response.setNextStoreId(store.getId());
                response.setEstimatedNextArrivalAt(now.plusSeconds((long) Math.ceil(road.getDuration())));
                firstLeg = false;
            }
            fromLat = store.getLat();
            fromLng = store.getLng();
        }
        OsrmRouteResponse.Route returnRoad = osrmClient.route(
                new double[]{fromLng, fromLat},
                new double[]{warehouse.getLng(), warehouse.getLat()});
        totalMeters += returnRoad.getDistance();
        totalSeconds += returnRoad.getDuration();
        response.setRemainingKm(totalMeters / 1000.0);
        response.setRemainingDriveMinutes((int) Math.ceil(totalSeconds / 60.0));
        response.setEstimatedReturnAt(now.plusSeconds((long) Math.ceil(totalSeconds)));
        response.setLiveEtaStatus(remaining.isEmpty() ? "RETURNING_TO_WAREHOUSE" : "COMPLETE");
    }

    private void validateCoordinates(Double lat, Double lng, String label) {
        if (lat == null || lng == null || !Double.isFinite(lat) || !Double.isFinite(lng)
                || lat < -90 || lat > 90 || lng < -180 || lng > 180) {
            throw new IllegalArgumentException(label + " 缺少有效經緯度");
        }
    }

    private static class Location {
        private final String name;
        private final double lat;
        private final double lng;
        private final Long orderId;
        private final Long storeId;

        private Location(String name, double lat, double lng, Long orderId, Long storeId) {
            this.name = name;
            this.lat = lat;
            this.lng = lng;
            this.orderId = orderId;
            this.storeId = storeId;
        }
    }

    private static class PlanCalculation {
        private final double totalMeters;
        private final int driveMinutes;
        private final int totalMinutes;
        private final Double kmPerLiter;
        private final Double pricePerLiter;
        private final Double plannedFuelLiters;
        private final Double plannedFuelCost;
        private final String fuelStatus;
        private final List<RouteMetricsResponse.Leg> legs;

        private PlanCalculation(
                double totalMeters,
                int driveMinutes,
                int totalMinutes,
                Double kmPerLiter,
                Double pricePerLiter,
                Double plannedFuelLiters,
                Double plannedFuelCost,
                String fuelStatus,
                List<RouteMetricsResponse.Leg> legs
        ) {
            this.totalMeters = totalMeters;
            this.driveMinutes = driveMinutes;
            this.totalMinutes = totalMinutes;
            this.kmPerLiter = kmPerLiter;
            this.pricePerLiter = pricePerLiter;
            this.plannedFuelLiters = plannedFuelLiters;
            this.plannedFuelCost = plannedFuelCost;
            this.fuelStatus = fuelStatus;
            this.legs = legs;
        }
    }
}
