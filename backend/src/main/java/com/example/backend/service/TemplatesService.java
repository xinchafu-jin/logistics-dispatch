package com.example.backend.service;

import com.example.backend.constants.DispatchDayStatus;
import com.example.backend.dao.*;
import com.example.backend.dto.request.OptimizeSlotsDTO;
import com.example.backend.dto.request.TemplatesRequestDTO;
import com.example.backend.dto.respones.DispatchResponse;
import com.example.backend.dto.respones.TemplatesDTO;
import com.example.backend.entity.DispatchTemplatesEntity;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.TemplateRoutesEntity;
import com.example.backend.entity.TemplateStopsEntity;
import com.example.backend.entity.VehiclesEntity;
import com.google.ortools.pdlp.OptimalityNorm;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.xml.transform.Templates;
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
    private final DriversDAO driversDAO;
    private final DispatchWorkflowService dispatchWorkflowService;


    public TemplatesService(
            DispatchTemplatesDAO dispatchTemplatesDAO,
            TemplateRoutesDAO templateRoutesDAO,
            TemplateStopsDAO templateStopsDAO,
            WarehousesDAO warehousesDAO,
            VehiclesDAO vehiclesDAO,
            StoresDAO storesDAO,
            DriversDAO driversDAO,
            DispatchWorkflowService dispatchWorkflowService
    ) {
        this.dispatchTemplatesDAO = dispatchTemplatesDAO;
        this.templateRoutesDAO = templateRoutesDAO;
        this.templateStopsDAO = templateStopsDAO;
        this.warehousesDAO = warehousesDAO;
        this.vehiclesDAO = vehiclesDAO;
        this.storesDAO = storesDAO;
        this.driversDAO = driversDAO;
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
     * 把編組套用到某一天：編組只存人和車，依格子交給 OR-Tools 重新排車，寫成那天的草稿。
     *
     * <p>每個倉各呼叫一次 {@link DispatchWorkflowService#optimizeSlots}，會取代那天那倉原本的草稿；
     * 自動配車、別倉已用掉的司機、已發布要擋，都由它處理。編組沒有的倉庫不受影響。</p>
     *
     * <p>類別上的 @Transactional 讓整次套用全有或全無：某一倉被擋下，前面排好的倉也一起回滾。</p>
     *
     * @return 編組裡每個倉各一包看板資料，notices 帶有自動配車等提醒
     */
    public List<DispatchResponse> applyToDate(Long templateId, LocalDate date) {
        TemplatesDTO template = findById(templateId);

        // 車輛調倉後，編組記的倉庫會跟車輛實際的倉庫對不上。
        // 不擋的話那條線會靜默消失（門市的訂單屬於舊倉，撈不到）
        for (TemplatesDTO.TemplateRouteResponse route : template.getRoutes()) {
            // 只有人沒有車的格子沒有車可檢查，車由 optimizeSlots 自動配
            if (route.getVehicleId() == null) {
                continue;
            }
            VehiclesEntity vehicle = vehiclesDAO.findById(route.getVehicleId())
                    .orElseThrow(() -> new EntityNotFoundException(
                            "找不到車輛，ID：" + route.getVehicleId()));
            if (!route.getWarehouseId().equals(vehicle.getWarehouseId())) {
                throw new IllegalArgumentException(
                        "車輛 " + vehicle.getPlateNumber() + " 已調至其他倉庫，請重新設定編組");
            }
        }

        // 編組可跨倉（每一格各帶 warehouseId），但 optimizeSlots 一次只處理一個倉。
        // 只填司機的格子也要分進來，不能像上面一樣跳過
        Map<Long, List<TemplatesDTO.TemplateRouteResponse>> byWarehouse = new LinkedHashMap<>();
        for (TemplatesDTO.TemplateRouteResponse route : template.getRoutes()) {
            byWarehouse.computeIfAbsent(route.getWarehouseId(), key -> new ArrayList<>())
                    .add(route);
        }

        List<DispatchResponse> boards = new ArrayList<>();
        for (Long warehouseId : byWarehouse.keySet()) {
            List<TemplatesDTO.TemplateRouteResponse> templateRoute = byWarehouse.get(warehouseId);
            OptimizeSlotsDTO dto = toOptimizeSlots(date, warehouseId, templateRoute);
            DispatchResponse board = dispatchWorkflowService.optimizeSlots(dto);
            boards.add(board);
        }
        return boards;
    }

    /** 編組的一個倉轉成依格子排車的請求：只帶人車，訂單交給 OR-Tools 分配 */
    private OptimizeSlotsDTO toOptimizeSlots(
            LocalDate date, Long warehouseId, List<TemplatesDTO.TemplateRouteResponse> templateRoutes
    ) {
        List<OptimizeSlotsDTO.Slot> slots = new ArrayList<>();
        for (TemplatesDTO.TemplateRouteResponse route : templateRoutes) {
            OptimizeSlotsDTO.Slot slot = new OptimizeSlotsDTO.Slot();
            slot.setDriverId(route.getDriverId());
            slot.setVehicleId(route.getVehicleId());
            slots.add(slot);
        }

        OptimizeSlotsDTO dto = new OptimizeSlotsDTO();
        dto.setDate(date);
        dto.setWarehouseId(warehouseId);
        dto.setSlots(slots);
        return dto;
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
            route.setDriverId(routeRequest.getDriverId());
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
        Set<Long> usedDriverIds = new HashSet<>();
        Set<Long> usedStoreIds = new HashSet<>();
        for (TemplatesRequestDTO.TemplateRouteRequest route : dto.getRoutes()) {
            Long warehouseId = route.getWarehouseId();
            if (!warehousesDAO.existsById(warehouseId)) {
                throw new IllegalArgumentException("找不到倉庫，ID：" + warehouseId);
            }

            Long vehicleId = route.getVehicleId();
            Long driverId = route.getDriverId();
            if (vehicleId == null && driverId == null) {
                throw new IllegalArgumentException("每一格至少要選司機或車輛");
            }
            if (vehicleId != null) {
                if (!usedVehicleIds.add(vehicleId)) {
                    throw new IllegalArgumentException("同一編組不能重複使用車輛，ID：" + vehicleId);
                }
                VehiclesEntity vehicle = vehiclesDAO.findById(vehicleId)
                        .orElseThrow(() -> new IllegalArgumentException("找不到車輛，ID：" + vehicleId));
                if (!warehouseId.equals(vehicle.getWarehouseId())) {
                    throw new IllegalArgumentException("車輛 " + vehicleId + " 不屬於倉庫 " + warehouseId);
                }
            }
            if (driverId != null) {
                // 編組套用到同一天，一位司機一天只能開一條線（uk_tpl_routes_tpl_driver 是最後一道）
                if (!usedDriverIds.add(driverId)) {
                    throw new IllegalArgumentException("同一編組不能重複使用司機，ID：" + driverId);
                }
                DriversEntity driver = driversDAO.findById(driverId)
                        .orElseThrow(() -> new IllegalArgumentException("找不到司機，ID：" + driverId));
                if (!Boolean.TRUE.equals(driver.getIsActive())) {
                    throw new IllegalArgumentException("司機「" + driver.getName() + "」已停用，不能放進編組");
                }
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
            routeResponse.setDriverId(route.getDriverId());

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
