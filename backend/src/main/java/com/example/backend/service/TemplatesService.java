package com.example.backend.service;

import com.example.backend.constants.OrderStatus;
import com.example.backend.dao.*;
import com.example.backend.dto.request.ReassignDTO;
import com.example.backend.dto.request.TemplatesRequestDTO;
import com.example.backend.dto.respones.DispatchResponse;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.dto.respones.TemplatesDTO;
import com.example.backend.entity.DispatchTemplatesEntity;
import com.example.backend.entity.TemplateRoutesEntity;
import com.example.backend.entity.TemplateStopsEntity;
import com.example.backend.entity.VehiclesEntity;
import com.sun.jna.platform.win32.COM.Dispatch;
import jakarta.persistence.EntityNotFoundException;
import org.hibernate.sql.Template;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

@Service
@Transactional
public class TemplatesService {
    private final DispatchTemplatesDAO dispatchTemplatesDAO;
    private final TemplateRoutesDAO templateRoutesDAO;
    private final TemplateStopsDAO templateStopsDAO;
    private final WarehousesDAO warehousesDAO;
    private final VehiclesDAO vehiclesDAO;
    private final StoresDAO storesDAO;
    private final OrdersDAO ordersDAO;
    private final RoutesDAO routesDAO;
    private final DispatchWorkflowService dispatchWorkflowService;


    public TemplatesService(
            DispatchTemplatesDAO dispatchTemplatesDAO,
            TemplateRoutesDAO templateRoutesDAO,
            TemplateStopsDAO templateStopsDAO,
            WarehousesDAO warehousesDAO,
            VehiclesDAO vehiclesDAO,
            StoresDAO storesDAO,
            OrdersDAO ordersDAO,
            RoutesDAO routesDAO,
            DispatchWorkflowService dispatchWorkflowService
    ) {
        this.dispatchTemplatesDAO = dispatchTemplatesDAO;
        this.templateRoutesDAO = templateRoutesDAO;
        this.templateStopsDAO = templateStopsDAO;
        this.warehousesDAO = warehousesDAO;
        this.vehiclesDAO = vehiclesDAO;
        this.storesDAO = storesDAO;
        this.ordersDAO = ordersDAO;
        this.routesDAO = routesDAO;
        this.dispatchWorkflowService = dispatchWorkflowService;
    }

    @Transactional(readOnly = true)
    public List<TemplatesDTO> findAll() {
        List<DispatchTemplatesEntity> templates = dispatchTemplatesDAO.findAll();

        Map<Long, List<TemplateRoutesEntity>> routesByTemplate = new HashMap<>();
        for (TemplateRoutesEntity route : templateRoutesDAO.findAll()) {
            List<TemplateRoutesEntity> list = routesByTemplate.get(route.getTemplateId());
            if (list == null) {
                list = new ArrayList<>();
                routesByTemplate.put(route.getTemplateId(), list);
            }
            list.add(route);
        }

        Map<Long, List<TemplateStopsEntity>> stopsByRoute = new HashMap<>();
        for (TemplateStopsEntity stop : templateStopsDAO.findAllByOrderBySequenceAsc()) {
            List<TemplateStopsEntity> list = stopsByRoute.get(stop.getTemplateRouteId());
            if (list == null) {
                list = new ArrayList<>();
                stopsByRoute.put(stop.getTemplateRouteId(), list);
            }
            list.add(stop);
        }

        // 三個 Map 都準備好了，逐一組成 DTO，迴圈裡不再碰資料庫
        List<TemplatesDTO> result = new ArrayList<>();
        for (DispatchTemplatesEntity template : templates) {
            result.add(toDTO(template, routesByTemplate, stopsByRoute));
        }
        return result;
    }

    public TemplatesDTO create(TemplatesRequestDTO dto) {
        if (dispatchTemplatesDAO.existsByName(dto.getName())) {
            throw new IllegalArgumentException("編組名稱已存在：" + dto.getName());
        }

        validateReferences(dto);

        DispatchTemplatesEntity template = new DispatchTemplatesEntity();
        apply(dto, template);
        dispatchTemplatesDAO.save(template);
        saveRoutesAndStops(template.getId(), dto.getRoutes());

        return findById(template.getId());
    }

    public TemplatesDTO update(Long id, TemplatesRequestDTO dto) {
        DispatchTemplatesEntity template = findEntity(id);
        boolean nameChanged = !template.getName().equals(dto.getName());
        if (nameChanged && dispatchTemplatesDAO.existsByName(dto.getName())) {
            throw new IllegalArgumentException("編組名稱已存在：" + dto.getName());
        }

        validateReferences(dto);
        deleteRoutesAndStops(id);
        apply(dto, template);
        dispatchTemplatesDAO.save(template);
        saveRoutesAndStops(id, dto.getRoutes());

        return findById(id);
    }

    public void delete(Long id) {
        DispatchTemplatesEntity template = findEntity(id);
        deleteRoutesAndStops(id);
        dispatchTemplatesDAO.delete(template);
    }

    public TemplatesDTO findById(Long id) {
        DispatchTemplatesEntity template = findEntity(id);
        List<TemplateRoutesEntity> routes = templateRoutesDAO.findByTemplateIdOrderByIdAsc(id);
        List<Long> routeIds = routes.stream().map(TemplateRoutesEntity::getId).toList();
        List<TemplateStopsEntity> stops = routeIds.isEmpty()
                ? List.of()
                : templateStopsDAO.findByTemplateRouteIdInOrderByTemplateRouteIdAscSequenceAsc(routeIds);

        Map<Long, List<TemplateRoutesEntity>> routesByTemplate = new HashMap<>();
        routesByTemplate.put(id, routes);
        Map<Long, List<TemplateStopsEntity>> stopsByRoute = new HashMap<>();
        for (TemplateStopsEntity stop : stops) {
            stopsByRoute.computeIfAbsent(stop.getTemplateRouteId(), key -> new ArrayList<>()).add(stop);
        }
        return toDTO(template, routesByTemplate, stopsByRoute);
    }

    /**
     * 把編組套用到某一天：依編組的車輛與門市，撈當天訂單組成路線。
     *
     * <p>編組存的是門市不是訂單（訂單綁日期、會取消，不能當樣板內容），
     * 所以每次套用都要現查那天有哪些單。實際建路線交給
     * {@link DispatchWorkflowService#reassign} —— 它會先檢查狀態，再安全重建草稿。</p>
     *
     * <p>套用後路線的司機是 null（編組不存司機），由調度員指派後才能發布。</p>
     *
     * @return 每個有排到路線的倉庫各一包看板資料
     */
    public List<DispatchResponse> applyToDate(Long templateId, LocalDate date) {
        TemplatesDTO template = findById(templateId);

        // 車輛調倉後，編組記的倉庫會跟車輛實際的倉庫對不上。
        // 不擋的話那條線會靜默消失（門市的訂單屬於舊倉，撈不到）
        for (TemplatesDTO.TemplateRouteResponse route : template.getRoutes()) {
            VehiclesEntity vehicle = vehiclesDAO.findById(route.getVehicleId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "找不到車輛，ID：" + route.getVehicleId()));
            if (!route.getWarehouseId().equals(vehicle.getWarehouseId())) {
                throw new IllegalArgumentException(
                        "車輛 " + vehicle.getPlateNumber() + " 已調至其他倉庫，請重新設定編組");
            }
        }

        // 編組可跨倉（第二層每列各帶 warehouseId），但 reassign 一次只處理一個倉
        Map<Long, List<TemplatesDTO.TemplateRouteResponse>> byWarehouse = new LinkedHashMap<>();
        for (TemplatesDTO.TemplateRouteResponse route : template.getRoutes()) {
            byWarehouse.computeIfAbsent(route.getWarehouseId(), key -> new ArrayList<>())
                    .add(route);
        }

        List<DispatchResponse> boards = new ArrayList<>();
        for (Map.Entry<Long, List<TemplatesDTO.TemplateRouteResponse>> entry : byWarehouse.entrySet()) {
            Long warehouseId = entry.getKey();
            List<TemplatesDTO.TemplateRouteResponse> templateRoutes = entry.getValue();

            // 這個倉所有停靠點的門市，一次撈完當天訂單，不要在迴圈裡逐間查
            Set<Long> storeIds = new LinkedHashSet<>();
            for (TemplatesDTO.TemplateRouteResponse route : templateRoutes) {
                for (TemplatesDTO.TemplateStopResponse stop : route.getStops()) {
                    storeIds.add(stop.getStoreId());
                }
            }
            if (storeIds.isEmpty()) {
                continue;
            }

            Set<Long> draftRouteIds = routesDAO.findByDateAndWarehouseId(date, warehouseId).stream()
                    .filter(route -> route.getStatus() == com.example.backend.constants.RouteStatus.DRAFT)
                    .map(route -> route.getId())
                    .collect(java.util.stream.Collectors.toSet());
            List<OrdersEntity> orders = ordersDAO.findByDeliveryDateAndWarehouseId(date, warehouseId)
                    .stream()
                    .filter(order -> order.getStatus() == OrderStatus.CONFIRMED)
                    .filter(order -> storeIds.contains(order.getStoreId()))
                    .filter(order -> order.getRouteId() == null
                            || draftRouteIds.contains(order.getRouteId()))
                    .toList();

            // 依門市分組，等一下照 stops 的順序逐站取用
            Map<Long, List<OrdersEntity>> ordersByStore = new HashMap<>();
            for (OrdersEntity order : orders) {
                ordersByStore.computeIfAbsent(order.getStoreId(), key -> new ArrayList<>())
                        .add(order);
            }

            List<ReassignDTO.RouteAssignment> assignments = new ArrayList<>();
            for (TemplatesDTO.TemplateRouteResponse route : templateRoutes) {
                // stops 已由 DAO 依 sequence 排好，陣列順序就是配送順序：
                // reassign 不重排，會原封成為 orders.sequence
                List<Long> orderIds = new ArrayList<>();
                for (TemplatesDTO.TemplateStopResponse stop : route.getStops()) {
                    List<OrdersEntity> storeOrders = ordersByStore.get(stop.getStoreId());
                    if (storeOrders == null) {
                        continue; // 這間門市當天沒單，跳過這站
                    }
                    for (OrdersEntity order : storeOrders) {
                        orderIds.add(order.getId());
                    }
                }
                if (orderIds.isEmpty()) {
                    continue; // 整條線都沒單，不建空車路線
                }

                ReassignDTO.RouteAssignment assignment = new ReassignDTO.RouteAssignment();
                assignment.setVehicleId(route.getVehicleId());
                // 編組不存司機，套用後由調度員指派，發布時才強制要求
                assignment.setDriverId(null);
                assignment.setOrderIds(orderIds);
                assignments.add(assignment);
            }

            // 這個倉當天完全沒單就不要呼叫 reassign，
            // 否則它會清掉當天草稿卻什麼都不建，等於把手動排的也刪了
            if (assignments.isEmpty()) {
                continue;
            }

            ReassignDTO dto = new ReassignDTO();
            dto.setDate(date);
            dto.setWarehouseId(warehouseId);
            dto.setRoutes(assignments);
            dispatchWorkflowService.reassign(dto);

            Set<Long> templateVehicleIds = templateRoutes.stream()
                    .map(TemplatesDTO.TemplateRouteResponse::getVehicleId)
                    .collect(java.util.stream.Collectors.toSet());
            List<com.example.backend.entity.RoutesEntity> appliedRoutes =
                    routesDAO.findByDateAndWarehouseId(date, warehouseId).stream()
                            .filter(route -> templateVehicleIds.contains(route.getVehicleId()))
                            .toList();
            for (com.example.backend.entity.RoutesEntity route : appliedRoutes) {
                route.setTemplateId(templateId);
            }
            routesDAO.saveAll(appliedRoutes);
            boards.add(dispatchWorkflowService.getBoard(date, warehouseId));
        }
        return boards;
    }

    private void apply(TemplatesRequestDTO dto, DispatchTemplatesEntity template) {
        template.setName(dto.getName());
        template.setNotes(dto.getNotes());
    }

    private void saveRoutesAndStops(
            Long templateId,
            List<TemplatesRequestDTO.TemplateRouteRequest> routeRequests
    ) {
        for (TemplatesRequestDTO.TemplateRouteRequest routeRequest : routeRequests) {
            TemplateRoutesEntity route = new TemplateRoutesEntity();
            route.setTemplateId(templateId);
            route.setWarehouseId(routeRequest.getWarehouseId());
            route.setVehicleId(routeRequest.getVehicleId());
            templateRoutesDAO.save(route);

            List<TemplateStopsEntity> stops = new ArrayList<>();
            for (int index = 0; index < routeRequest.getStoreIds().size(); index++) {
                TemplateStopsEntity stop = new TemplateStopsEntity();
                stop.setTemplateRouteId(route.getId());
                stop.setStoreId(routeRequest.getStoreIds().get(index));
                stop.setSequence(index + 1);
                stops.add(stop);
            }
            templateStopsDAO.saveAll(stops);
        }
    }

    private void deleteRoutesAndStops(Long templateId) {
        List<TemplateRoutesEntity> routes = templateRoutesDAO.findByTemplateIdOrderByIdAsc(templateId);
        if (routes.isEmpty()) {
            return;
        }

        List<Long> routeIds = routes.stream().map(TemplateRoutesEntity::getId).toList();
        List<TemplateStopsEntity> stops =
                templateStopsDAO.findByTemplateRouteIdInOrderByTemplateRouteIdAscSequenceAsc(routeIds);
        templateStopsDAO.deleteAllInBatch(stops);
        templateRoutesDAO.deleteAllInBatch(routes);
    }

    private void validateReferences(TemplatesRequestDTO dto) {
        Set<Long> usedVehicleIds = new HashSet<>();
        Set<Long> usedStoreIds = new HashSet<>();
        for (TemplatesRequestDTO.TemplateRouteRequest route : dto.getRoutes()) {
            Long warehouseId = route.getWarehouseId();
            if (!warehousesDAO.existsById(warehouseId)) {
                throw new IllegalArgumentException("找不到倉庫，ID：" + warehouseId);
            }

            Long vehicleId = route.getVehicleId();
            if (!usedVehicleIds.add(vehicleId)) {
                throw new IllegalArgumentException("同一編組不能重複使用車輛，ID：" + vehicleId);
            }
            VehiclesEntity vehicle = vehiclesDAO.findById(vehicleId)
                    .orElseThrow(() -> new IllegalArgumentException("找不到車輛，ID：" + vehicleId));
            if (!warehouseId.equals(vehicle.getWarehouseId())) {
                throw new IllegalArgumentException("車輛 " + vehicleId + " 不屬於倉庫 " + warehouseId);
            }

            Set<Long> storesInThisRoute = new HashSet<>();
            for (Long storeId : route.getStoreIds()) {
                if (!storesInThisRoute.add(storeId)) {
                    throw new IllegalArgumentException("同一路線不能重複出現門市，ID：" + storeId);
                }
                if (!usedStoreIds.add(storeId)) {
                    throw new IllegalArgumentException("同一門市不能同時出現在兩條路線，ID：" + storeId);
                }
                if (!storesDAO.existsById(storeId)) {
                    throw new IllegalArgumentException("找不到門市，ID：" + storeId);
                }
            }
        }
    }

    private DispatchTemplatesEntity findEntity(Long id) {
        return dispatchTemplatesDAO.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("找不到編組，ID：" + id));
    }

    private TemplatesDTO toDTO(
            DispatchTemplatesEntity template,
            Map<Long, List<TemplateRoutesEntity>> routesByTemplate,
            Map<Long, List<TemplateStopsEntity>> stopsByRoute
    ) {
        TemplatesDTO dto = new TemplatesDTO();
        dto.setId(template.getId());
        dto.setName(template.getName());
        dto.setNotes(template.getNotes());

        List<TemplateRoutesEntity> routes = routesByTemplate.get(template.getId());
        if (routes == null) {
            routes = new ArrayList<>();
        }

        List<TemplatesDTO.TemplateRouteResponse> routeResponses = new ArrayList<>();
        for (TemplateRoutesEntity route : routes) {
            TemplatesDTO.TemplateRouteResponse routeResponse = new TemplatesDTO.TemplateRouteResponse();
            routeResponse.setId(route.getId());
            routeResponse.setWarehouseId(route.getWarehouseId());
            routeResponse.setVehicleId(route.getVehicleId());

            List<TemplateStopsEntity> stops = stopsByRoute.get(route.getId());
            if (stops == null) {
                stops = new ArrayList<>();
            }

            List<TemplatesDTO.TemplateStopResponse> stopResponses = new ArrayList<>();
            for (TemplateStopsEntity stop : stops) {
                TemplatesDTO.TemplateStopResponse stopResponse = new TemplatesDTO.TemplateStopResponse();
                stopResponse.setId(stop.getId());
                stopResponse.setStoreId(stop.getStoreId());
                stopResponse.setSequence(stop.getSequence());
                stopResponses.add(stopResponse);
            }
            routeResponse.setStops(stopResponses);
            routeResponses.add(routeResponse);
        }
        dto.setRoutes(routeResponses);
        return dto;
    }
}
