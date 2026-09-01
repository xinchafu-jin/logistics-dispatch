package com.example.backend.service;

import com.example.backend.dao.DispatchTemplatesDAO;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.TemplateRoutesDAO;
import com.example.backend.dao.TemplateStopsDAO;
import com.example.backend.dao.VehiclesDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dto.request.TemplatesRequestDTO;
import com.example.backend.dto.respones.TemplatesDTO;
import com.example.backend.entity.DispatchTemplatesEntity;
import com.example.backend.entity.TemplateRoutesEntity;
import com.example.backend.entity.TemplateStopsEntity;
import com.example.backend.entity.VehiclesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class TemplatesService {
    private final DispatchTemplatesDAO dispatchTemplatesDAO;
    private final TemplateRoutesDAO templateRoutesDAO;
    private final TemplateStopsDAO templateStopsDAO;
    private final WarehousesDAO warehousesDAO;
    private final VehiclesDAO vehiclesDAO;
    private final StoresDAO storesDAO;

    public TemplatesService(
            DispatchTemplatesDAO dispatchTemplatesDAO,
            TemplateRoutesDAO templateRoutesDAO,
            TemplateStopsDAO templateStopsDAO,
            WarehousesDAO warehousesDAO,
            VehiclesDAO vehiclesDAO,
            StoresDAO storesDAO
    ) {
        this.dispatchTemplatesDAO = dispatchTemplatesDAO;
        this.templateRoutesDAO = templateRoutesDAO;
        this.templateStopsDAO = templateStopsDAO;
        this.warehousesDAO = warehousesDAO;
        this.vehiclesDAO = vehiclesDAO;
        this.storesDAO = storesDAO;
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
        for (TemplatesRequestDTO.TemplateRouteRequest route : dto.getRoutes()) {
            Long warehouseId = route.getWarehouseId();
            if (!warehousesDAO.existsById(warehouseId)) {
                throw new IllegalArgumentException("找不到倉庫，ID：" + warehouseId);
            }

            Long vehicleId = route.getVehicleId();
            VehiclesEntity vehicle = vehiclesDAO.findById(vehicleId)
                    .orElseThrow(() -> new IllegalArgumentException("找不到車輛，ID：" + vehicleId));
            if (!warehouseId.equals(vehicle.getWarehouseId())) {
                throw new IllegalArgumentException("車輛 " + vehicleId + " 不屬於倉庫 " + warehouseId);
            }

            for (Long storeId : route.getStoreIds()) {
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
