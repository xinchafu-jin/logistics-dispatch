package com.example.backend.service;

import com.example.backend.constants.RouteLegLocationType;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.*;
import com.example.backend.dispatch.OsrmClient;
import com.example.backend.dispatch.OsrmRouteResponse;
import com.example.backend.dto.respones.FuelPriceResponse;
import com.example.backend.dto.respones.PlannedPathResponse;
import com.example.backend.dto.respones.RouteMetricsResponse;
import com.example.backend.entity.*;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
// Jackson 3（Spring Boot 4 自動建立的 bean）；不是 com.fasterxml 的 Jackson 2，那個沒有 bean，注入會啟動失敗
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 計算含回倉庫的 OSRM 路線時間、里程及油耗預估。
 */
@Service
public class RoutePlanMetricsService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final RoutePlannedLegsDAO routePlannedLegsDAO;
    // 把路線形狀轉成 JSON 字串存進 route_planned_legs.path
    private final JsonMapper jsonMapper;
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

    // 只能有一個建構子：有兩個又都沒標 @Autowired 時，Spring 不知道用哪個，會改找無參數建構子而啟動失敗。
    // 之後要多注入東西，就在這個建構子加參數，不要另外產生一個新的
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
            RoutePlannedLegsDAO routePlannedLegsDAO,
            JsonMapper jsonMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${app.gps.freshness-minutes:10}") int gpsFreshnessMinutes
    ) {
        this.routePlannedLegsDAO = routePlannedLegsDAO;
        this.jsonMapper = jsonMapper;
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

    /**
     * 發布前保存現有 routes 欄位能承載的總里程、總工時與預估油費。
     */
    @Transactional
    public void calculateAndStore(LocalDate date) {
        List<RoutesEntity> routes = routesDAO.findByDate(date);
        // 先刪掉這一天所有路線的舊形狀：撤回後重新發布、或這條路線這次被跳過，都不能留著上一次的
        deletePlannedLegs(routes);
        List<RoutePlannedLegsEntity> plannedLegs = new ArrayList<>();
        for (RoutesEntity route : routes) {
            boolean hasActiveOrders = ordersDAO.findByRouteIdOrderBySequence(route.getId()).stream()
                    .anyMatch(order -> order.getStatus().isActive());
            if (!hasActiveOrders) {
                continue;
            }
            PlanCalculation calculation = calculatePlan(route, true, true);
            route.setTotalDistance(calculation.totalMeters);
            route.setEstimatedWorkMinutes(calculation.totalMinutes);
            route.setEstimatedFuelCost(calculation.plannedFuelCost);
            plannedLegs.addAll(calculation.plannedLegs);
        }
        routesDAO.saveAll(routes);
        routePlannedLegsDAO.saveAll(plannedLegs);
    }

    /** 撤回時呼叫：路線變回草稿後可能被改，留著舊形狀會畫錯、比錯 */
    @Transactional
    public void deletePlannedPaths(LocalDate date) {
        deletePlannedLegs(routesDAO.findByDate(date));
    }

    private void deletePlannedLegs(List<RoutesEntity> routes) {
        List<Long> routeIds = new ArrayList<>();
        for (RoutesEntity route : routes) {
            routeIds.add(route.getId());
        }
        // 空的 IN () 在 MySQL 是語法錯誤，沒有路線就不查
        if (!routeIds.isEmpty()) {
            routePlannedLegsDAO.deleteAllForRoutes(routeIds);
        }
    }

    /**
     * 後台地圖畫已發布路線用：這一天、這個倉庫每條已發布路線的各段道路形狀。
     *
     * <p>沒有形狀的路線不會出現在回傳裡（例如 V8 上線前就發布的、假資料腳本直接寫成已發布的），
     * 前端找不到就退回畫直線。</p>
     */
    @Transactional(readOnly = true)
    public List<PlannedPathResponse> getPlannedPaths(LocalDate date, Long warehouseId) {
        List<Long> routeIds = new ArrayList<>();
        for (RoutesEntity route : routesDAO.findByDateAndWarehouseId(date, warehouseId)) {
            // 形狀只在發布時存、撤回時刪，照理只有已發布的路線有；這裡再擋一次，草稿不畫道路線
            if (route.getStatus() == RouteStatus.PUBLISHED) {
                routeIds.add(route.getId());
            }
        }
        // 空的 IN () 在 MySQL 是語法錯誤，沒有路線就不查
        if (routeIds.isEmpty()) {
            return List.of();
        }
        // DAO 依路線、段號排好了，同一條路線的段會連在一起，照順序放進去就是行駛順序
        Map<Long, PlannedPathResponse> pathByRoute = new LinkedHashMap<>();
        for (RoutePlannedLegsEntity plannedLeg
                : routePlannedLegsDAO.findAllByRouteIdInOrderByRouteIdAscSequenceAsc(routeIds)) {
            pathByRoute.computeIfAbsent(plannedLeg.getRouteId(), routeId -> new PlannedPathResponse(routeId))
                    .getLegs().add(toPathLeg(plannedLeg));
        }
        return new ArrayList<>(pathByRoute.values());
    }

    private PlannedPathResponse.Leg toPathLeg(RoutePlannedLegsEntity plannedLeg) {
        PlannedPathResponse.Leg leg = new PlannedPathResponse.Leg();
        leg.setSequence(plannedLeg.getSequence());
        leg.setToStoreId(plannedLeg.getToStoreId());
        // 存的時候是 jsonMapper 把 double[][] 寫成字串，這裡原樣解析回來
        leg.setPath(jsonMapper.readValue(plannedLeg.getPath(), double[][].class));
        return leg;
    }

    @Transactional(readOnly = true)
    public RouteMetricsResponse getMetrics(Long routeId) {
        RoutesEntity route = routesDAO.findById(routeId)
                .orElseThrow(() -> new EntityNotFoundException("找不到路線，ID：" + routeId));
        boolean hasActiveOrders = ordersDAO.findByRouteIdOrderBySequence(route.getId()).stream()
                .anyMatch(order -> order.getStatus().isActive());
        PlanCalculation plan = calculatePlan(route, hasActiveOrders, false);

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

            Double segmentKm = null;
            if (mileage.getMileageSettledAt() != null) {
                segmentKm = mileage.getGpsDistanceKm();
            }
            if (segmentKm == null) {
                LocalDateTime end = mileage.getEndTime();
                if (end == null) {
                    allComplete = false;
                    if (route.getDate().equals(LocalDate.now(TAIPEI))) {
                        end = LocalDateTime.now(TAIPEI);
                    } else {
                        end = route.getDate().atTime(LocalTime.MAX);
                    }
                }
                Double endLat = null;
                Double endLng = null;
                if (mileage.getEndTime() != null) {
                    endLat = warehouse.getLat();
                    endLng = warehouse.getLng();
                }
                GpsDistanceService.DistanceResult gps = gpsDistanceService.calculate(
                        mileage.getDriverId(), mileage.getStartTime(), end,
                        warehouse.getLat(), warehouse.getLng(),
                        endLat, endLng);
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
            if (segmentProblem == null) {
                response.setMileageStatus("NO_GPS_DISTANCE");
            } else {
                response.setMileageStatus(segmentProblem);
            }
            return response;
        }
        response.setGpsEstimatedKm(totalKm);
        if (mileageSegments.size() > 1) {
            if (allComplete) {
                response.setMileageStatus("COMPLETE_ROUTE_HANDOVER_SUMMED");
            } else {
                response.setMileageStatus("IN_PROGRESS_ROUTE_HANDOVER_SUMMED");
            }
        } else if (allComplete) {
            response.setMileageStatus("COMPLETE");
        } else {
            response.setMileageStatus("IN_PROGRESS");
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

    private PlanCalculation calculatePlan(RoutesEntity route, boolean activeOnly, boolean withPath) {
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
        Double pricePerLiter = null;
        if (fuelPrice != null) {
            pricePerLiter = fuelPrice.getPricePerLiter().doubleValue();
        }

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
        List<RoutePlannedLegsEntity> plannedLegs = new ArrayList<>();
        for (int index = 1; index < locations.size(); index++) {
            Location from = locations.get(index - 1);
            Location to = locations.get(index);
            double[] fromPoint = {from.lng, from.lat};
            double[] toPoint = {to.lng, to.lat};
            OsrmRouteResponse.Route road;
            if (withPath) {
                // 發布時：一次拿到距離、時間和道路形狀，形狀要存起來畫地圖、比對偏離
                road = osrmClient.routeGeometry(fromPoint, toPoint);
            } else {
                // 看路線明細：只要距離和時間，不拿形狀（一段約 0.6 KB，拿形狀約 2.5 KB）
                road = osrmClient.route(fromPoint, toPoint);
            }
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
            if (withPath) {
                plannedLegs.add(toPlannedLeg(route.getId(), index, from, to, road));
            }
        }

        int driveMinutes = (int) Math.ceil(totalSeconds / 60.0);
        Double plannedFuelLiters = null;
        if (kmPerLiter != null) {
            plannedFuelLiters = totalMeters / 1000.0 / kmPerLiter;
        }
        Double plannedFuelCost = null;
        if (plannedFuelLiters != null && pricePerLiter != null) {
            plannedFuelCost = plannedFuelLiters * pricePerLiter;
        }
        String fuelStatus;
        if (kmPerLiter == null) {
            fuelStatus = "MISSING_VEHICLE_FUEL_CONSUMPTION";
        } else if (pricePerLiter == null) {
            fuelStatus = "MISSING_FUEL_PRICE";
        } else {
            fuelStatus = "COMPLETE";
        }
        return new PlanCalculation(totalMeters, driveMinutes, driveMinutes,
                kmPerLiter, pricePerLiter,
                plannedFuelLiters, plannedFuelCost, fuelStatus, legs, plannedLegs);
    }

    /**
     * 把一段 OSRM 結果轉成要存的預定路線；段號、起訖跟 RouteMetricsResponse.Leg 同一套
     */
    private RoutePlannedLegsEntity toPlannedLeg(Long routeId, int sequence,
                                                Location from, Location to, OsrmRouteResponse.Route road) {
        if (road.getGeometry() == null) {
            // 寧可讓發布失敗，也不要存進字串 "null"，之後畫圖、比對時才出錯很難查
            throw new IllegalStateException("OSRM 沒有回傳路線形狀：" + from.name + " → " + to.name);
        }
        RoutePlannedLegsEntity leg = new RoutePlannedLegsEntity();
        leg.setRouteId(routeId);
        leg.setSequence(sequence);
        leg.setFromType(locationType(from));
        leg.setFromStoreId(from.storeId);
        leg.setToType(locationType(to));
        leg.setToStoreId(to.storeId);
        leg.setDistanceMeters(road.getDistance());
        leg.setDurationSeconds(road.getDuration());
        // double[][] 轉成 "[[經度,緯度],…]"；Jackson 3 的例外是非受檢的，不用 try/catch
        leg.setPath(jsonMapper.writeValueAsString(road.getGeometry().getCoordinates()));
        return leg;
    }

    /**
     * calculatePlan 組的 Location 裡，倉庫的 storeId 是 null
     */
    private RouteLegLocationType locationType(Location location) {
        if (location.storeId == null) {
            return RouteLegLocationType.WAREHOUSE;
        }
        return RouteLegLocationType.STORE;
    }


    private void populateLiveEta(RouteMetricsResponse response, RoutesEntity route) {
        if (route.getStatus() != RouteStatus.PUBLISHED) {
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
        if (remaining.isEmpty()) {
            response.setLiveEtaStatus("RETURNING_TO_WAREHOUSE");
        } else {
            response.setLiveEtaStatus("COMPLETE");
        }
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
        //發布時才有內容：每一段的道路形狀，交給 calculateAndStore 存檔；看明細時是空清單
        private final List<RoutePlannedLegsEntity> plannedLegs;

        private PlanCalculation(
                double totalMeters,
                int driveMinutes,
                int totalMinutes,
                Double kmPerLiter,
                Double pricePerLiter,
                Double plannedFuelLiters,
                Double plannedFuelCost,
                String fuelStatus,
                List<RouteMetricsResponse.Leg> legs,
                List<RoutePlannedLegsEntity> plannedLegs
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
            this.plannedLegs = plannedLegs;
        }
    }
}
