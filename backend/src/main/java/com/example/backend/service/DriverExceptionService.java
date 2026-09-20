package com.example.backend.service;

import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.ExceptionCasesDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dto.request.DriverExceptionRequestDTO;
import com.example.backend.dto.respones.ExceptionCaseResponse;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.ExceptionCasesEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;

/** 司機一般配送異常回報，不會自行改變訂單或路線狀態。 */
@Service
public class DriverExceptionService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final DriversDAO driversDAO;
    private final OrdersDAO ordersDAO;
    private final RoutesDAO routesDAO;
    private final ExceptionCasesDAO exceptionCasesDAO;
    private final DeliveryExceptionService deliveryExceptionService;

    public DriverExceptionService(
            DriversDAO driversDAO,
            OrdersDAO ordersDAO,
            RoutesDAO routesDAO,
            ExceptionCasesDAO exceptionCasesDAO,
            DeliveryExceptionService deliveryExceptionService
    ) {
        this.driversDAO = driversDAO;
        this.ordersDAO = ordersDAO;
        this.routesDAO = routesDAO;
        this.exceptionCasesDAO = exceptionCasesDAO;
        this.deliveryExceptionService = deliveryExceptionService;
    }

    @Transactional
    public ExceptionCaseResponse report(Long driverId, DriverExceptionRequestDTO request) {
        DriversEntity driver = driversDAO.findById(driverId)
                .orElseThrow(() -> new EntityNotFoundException("找不到司機，ID：" + driverId));
        if (!Boolean.TRUE.equals(driver.getIsActive())) {
            throw new IllegalArgumentException("司機帳號目前未啟用");
        }
        OrdersEntity order = ordersDAO.findForUpdate(request.getOrderId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到訂單，ID：" + request.getOrderId()));
        if (order.getRouteId() == null) {
            throw new IllegalArgumentException("訂單尚未排入配送路線");
        }
        RoutesEntity route = routesDAO.findById(order.getRouteId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到路線，ID：" + order.getRouteId()));
        if (route.getStatus() != RouteStatus.PUBLISHED
                || !driverId.equals(route.getDriverId())
                || !LocalDate.now(TAIPEI).equals(route.getDate())) {
            throw new IllegalArgumentException("只能回報本人今天已發布路線的異常");
        }
        if (order.getStatus() != OrderStatus.CONFIRMED
                && order.getStatus() != OrderStatus.IN_DELIVERY) {
            throw new IllegalArgumentException("訂單已結案，不能新增配送異常：" + order.getStatus());
        }

        ExceptionCasesEntity exceptionCase = new ExceptionCasesEntity();
        exceptionCase.setOrderId(order.getId());
        exceptionCase.setType(ExceptionType.DRIVER_REPORT);
        exceptionCase.setDescription(request.getDescription().trim());
        exceptionCase.setStatus(ExceptionStatus.OPEN);
        exceptionCase = exceptionCasesDAO.save(exceptionCase);
        return deliveryExceptionService.toResponse(exceptionCase);
    }
}
