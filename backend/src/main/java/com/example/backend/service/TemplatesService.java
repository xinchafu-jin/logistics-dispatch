package com.example.backend.service;

import com.example.backend.dao.DispatchTemplatesDAO;
import com.example.backend.dao.TemplateRoutesDAO;
import com.example.backend.dao.TemplateStopsDAO;
import com.example.backend.dto.respones.TemplatesDTO;
import com.example.backend.entity.DispatchTemplatesEntity;
import com.example.backend.entity.TemplateRoutesEntity;
import com.example.backend.entity.TemplateStopsEntity;
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

    public TemplatesService(DispatchTemplatesDAO dispatchTemplatesDAO, TemplateRoutesDAO templateRoutesDAO, TemplateStopsDAO templateStopsDAO) {
        this.dispatchTemplatesDAO = dispatchTemplatesDAO;
        this.templateRoutesDAO = templateRoutesDAO;
        this.templateStopsDAO = templateStopsDAO;
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