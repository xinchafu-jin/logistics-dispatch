package com.example.backend.service;

import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.DeliveryRecordsDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dao.ExceptionCasesDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dto.request.ArriveRequestDTO;
import com.example.backend.dto.request.DeliverRequestDTO;
import com.example.backend.dto.request.NoSignatureRequestDTO;
import com.example.backend.dto.respones.DeliveryRecordResponse;
import com.example.backend.entity.DeliveryRecordsEntity;
import com.example.backend.entity.DriversEntity;
import com.example.backend.entity.ExceptionCasesEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static com.example.backend.constants.ValidMsg.*;

/** 司機抵達、完成交貨與無人簽收的共用交易流程。 */
@Service
@Transactional
public class DeliveryService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final long NO_SIGNATURE_WAIT_MINUTES = 10L;

    private final DeliveryRecordsDAO deliveryRecordsDAO;
    private final ExceptionCasesDAO exceptionCasesDAO;
    private final OrdersDAO ordersDAO;
    private final RoutesDAO routesDAO;
    private final DriversDAO driversDAO;

    public DeliveryService(
            DeliveryRecordsDAO deliveryRecordsDAO,
            ExceptionCasesDAO exceptionCasesDAO,
            OrdersDAO ordersDAO,
            RoutesDAO routesDAO,
            DriversDAO driversDAO
    ) {
        this.deliveryRecordsDAO = deliveryRecordsDAO;
        this.exceptionCasesDAO = exceptionCasesDAO;
        this.ordersDAO = ordersDAO;
        this.routesDAO = routesDAO;
        this.driversDAO = driversDAO;
    }

    /** 抵達門市後建立本次配送紀錄，訂單進入配送中。 */
    public DeliveryRecordResponse arrive(Long driverId, ArriveRequestDTO request) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        OrdersEntity order = findAuthorizedOrderForUpdate(driverId, request.getOrderId(), now.toLocalDate());
        requireOrderStatus(order, OrderStatus.CONFIRMED, DELIVERY_ARRIVE_STATUS_INVALID);

        if (deliveryRecordsDAO.findFirstByOrderIdOrderByIdDesc(order.getId())
                .filter(this::isInProgress)
                .isPresent()) {
            throw new IllegalArgumentException(DELIVERY_ALREADY_ARRIVED);
        }

        DeliveryRecordsEntity record = new DeliveryRecordsEntity();
        record.setOrderId(order.getId());
        record.setArrivedAt(now);
        record.setNoSignature(false);

        order.setStatus(OrderStatus.IN_DELIVERY);
        ordersDAO.save(order);
        return toResponse(deliveryRecordsDAO.save(record), order, null);
    }

    /** 完成交貨，保存實際箱數與照片並將訂單設為完成。 */
    public DeliveryRecordResponse deliver(Long driverId, DeliverRequestDTO request) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        OrdersEntity order = findAuthorizedOrderForUpdate(driverId, request.getOrderId(), now.toLocalDate());
        requireOrderStatus(order, OrderStatus.IN_DELIVERY, DELIVERY_DELIVER_STATUS_INVALID);

        if (!order.getBoxCount().equals(request.getBoxCount())) {
            throw new IllegalArgumentException(DELIVERY_BOX_COUNT_MISMATCH.formatted(order.getBoxCount()));
        }

        DeliveryRecordsEntity record = findInProgressRecord(order.getId());
        record.setDeliveredAt(now);
        record.setDeliveredBoxCount(request.getBoxCount());
        record.setPhotoUrl(request.getPhoto().trim());
        record.setNotes(trimToNull(request.getNotes()));
        record.setNoSignature(false);

        order.setStatus(OrderStatus.COMPLETED);
        ordersDAO.save(order);
        return toResponse(deliveryRecordsDAO.save(record), order, null);
    }

    /** 無人簽收會同時結束本次嘗試、建立待處理異常並將訂單設為失敗。 */
    public DeliveryRecordResponse noSignature(Long driverId, NoSignatureRequestDTO request) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        OrdersEntity order = findAuthorizedOrderForUpdate(driverId, request.getOrderId(), now.toLocalDate());
        requireOrderStatus(order, OrderStatus.IN_DELIVERY, DELIVERY_NO_SIGNATURE_STATUS_INVALID);

        DeliveryRecordsEntity record = findInProgressRecord(order.getId());
        if (now.isBefore(record.getArrivedAt().plusMinutes(NO_SIGNATURE_WAIT_MINUTES))) {
            throw new IllegalArgumentException(
                    DELIVERY_NO_SIGNATURE_WAIT_REQUIRED.formatted(NO_SIGNATURE_WAIT_MINUTES));
        }
        record.setDeliveredBoxCount(0);
        record.setPhotoUrl(request.getPhoto().trim());
        record.setNotes(trimToNull(request.getNotes()));
        record.setNoSignature(true);

        ExceptionCasesEntity exceptionCase = new ExceptionCasesEntity();
        exceptionCase.setOrderId(order.getId());
        exceptionCase.setType(ExceptionType.NO_SIGNATURE);
        exceptionCase.setDescription(
                record.getNotes() == null ? DELIVERY_NO_SIGNATURE_DESCRIPTION : record.getNotes());
        exceptionCase.setStatus(ExceptionStatus.OPEN);
        exceptionCase = exceptionCasesDAO.save(exceptionCase);

        order.setStatus(OrderStatus.FAILED);
        ordersDAO.save(order);
        return toResponse(deliveryRecordsDAO.save(record), order, exceptionCase.getId());
    }

    private OrdersEntity findAuthorizedOrderForUpdate(Long driverId, Long orderId, LocalDate today) {
        requireActiveDriver(driverId);
        OrdersEntity order = ordersDAO.findForUpdate(orderId)
                .orElseThrow(() -> new EntityNotFoundException(
                        DELIVERY_ORDER_NOT_FOUND.formatted(orderId)));
        if (order.getRouteId() == null) {
            throw new IllegalArgumentException(DELIVERY_ROUTE_REQUIRED);
        }

        RoutesEntity route = routesDAO.findById(order.getRouteId())
                .orElseThrow(() -> new EntityNotFoundException(
                        DELIVERY_ROUTE_NOT_FOUND.formatted(order.getRouteId())));
        if (route.getStatus() != RouteStatus.PUBLISHED) {
            throw new IllegalArgumentException(DELIVERY_ROUTE_NOT_PUBLISHED);
        }
        if (!today.equals(route.getDate())) {
            throw new IllegalArgumentException(DELIVERY_TODAY_ONLY);
        }
        if (!driverId.equals(route.getDriverId())) {
            throw new IllegalArgumentException(DELIVERY_WRONG_DRIVER);
        }
        if (order.getAssignedDriverId() != null
                && !driverId.equals(order.getAssignedDriverId())) {
            throw new IllegalArgumentException(DELIVERY_DRIVER_MISMATCH);
        }
        return order;
    }

    private void requireActiveDriver(Long driverId) {
        DriversEntity driver = driversDAO.findById(driverId)
                .orElseThrow(() -> new EntityNotFoundException(
                        DELIVERY_DRIVER_NOT_FOUND.formatted(driverId)));
        if (!Boolean.TRUE.equals(driver.getIsActive())) {
            throw new IllegalArgumentException(DELIVERY_DRIVER_INACTIVE);
        }
    }

    private DeliveryRecordsEntity findInProgressRecord(Long orderId) {
        DeliveryRecordsEntity record = deliveryRecordsDAO.findFirstByOrderIdOrderByIdDesc(orderId)
                .orElseThrow(() -> new IllegalArgumentException(DELIVERY_NOT_ARRIVED));
        if (!isInProgress(record)) {
            throw new IllegalArgumentException(DELIVERY_ALREADY_FINISHED);
        }
        return record;
    }

    private boolean isInProgress(DeliveryRecordsEntity record) {
        return record.getArrivedAt() != null
                && record.getDeliveredAt() == null
                && !Boolean.TRUE.equals(record.getNoSignature());
    }

    private void requireOrderStatus(OrdersEntity order, OrderStatus expected, String message) {
        if (order.getStatus() != expected) {
            throw new IllegalArgumentException(
                    message + DELIVERY_STATUS_SUFFIX.formatted(order.getStatus()));
        }
    }

    private String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private DeliveryRecordResponse toResponse(
            DeliveryRecordsEntity record,
            OrdersEntity order,
            Long exceptionCaseId
    ) {
        return new DeliveryRecordResponse(
                record.getId(),
                record.getOrderId(),
                order.getStatus(),
                record.getArrivedAt(),
                record.getDeliveredAt(),
                record.getDeliveredBoxCount(),
                record.getPhotoUrl(),
                record.getNotes(),
                record.getNoSignature(),
                exceptionCaseId
        );
    }
}
