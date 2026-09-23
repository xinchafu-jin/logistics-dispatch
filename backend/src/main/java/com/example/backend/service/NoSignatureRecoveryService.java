package com.example.backend.service;

import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.RouteStatus;
import com.example.backend.dao.DeliveryRecordsDAO;
import com.example.backend.dao.ExceptionCasesDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.dto.respones.ExceptionCaseResponse;
import com.example.backend.entity.DeliveryRecordsEntity;
import com.example.backend.entity.ExceptionCasesEntity;
import com.example.backend.entity.OrdersEntity;
import com.example.backend.entity.RoutesEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;

/** 主管修正司機誤按無人簽收，安全恢復原配送而不產生雙重訂單。 */
@Service
public class NoSignatureRecoveryService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final ExceptionCasesDAO exceptionCasesDAO;
    private final OrdersDAO ordersDAO;
    private final RoutesDAO routesDAO;
    private final DeliveryRecordsDAO deliveryRecordsDAO;
    private final DeliveryExceptionService deliveryExceptionService;

    public NoSignatureRecoveryService(
            ExceptionCasesDAO exceptionCasesDAO,
            OrdersDAO ordersDAO,
            RoutesDAO routesDAO,
            DeliveryRecordsDAO deliveryRecordsDAO,
            DeliveryExceptionService deliveryExceptionService
    ) {
        this.exceptionCasesDAO = exceptionCasesDAO;
        this.ordersDAO = ordersDAO;
        this.routesDAO = routesDAO;
        this.deliveryRecordsDAO = deliveryRecordsDAO;
        this.deliveryExceptionService = deliveryExceptionService;
    }

    @Transactional
    public ExceptionCaseResponse restoreDelivery(Long exceptionCaseId, String reviewedBy) {
        LocalDateTime now = LocalDateTime.now(TAIPEI);
        ExceptionCasesEntity exceptionCase = exceptionCasesDAO.findForUpdate(exceptionCaseId)
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到配送異常，ID：" + exceptionCaseId));

        if (exceptionCase.getType() != ExceptionType.NO_SIGNATURE) {
            throw new IllegalArgumentException("只有無人簽收案件可以恢復原單配送");
        }
        if (exceptionCase.getStatus() != ExceptionStatus.OPEN) {
            throw new IllegalArgumentException("此配送異常已經結案，不能重複恢復");
        }
        if (exceptionCase.getOrderId() == null || exceptionCase.getFollowUpOrderId() == null) {
            throw new IllegalArgumentException("異常案件缺少原訂單或隔日補送單，無法自動恢復");
        }

        OrdersEntity sourceOrder = ordersDAO.findForUpdate(exceptionCase.getOrderId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到來源訂單，ID：" + exceptionCase.getOrderId()));
        OrdersEntity followUpOrder = ordersDAO.findForUpdate(exceptionCase.getFollowUpOrderId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到後續訂單，ID：" + exceptionCase.getFollowUpOrderId()));

        if (sourceOrder.getStatus() != OrderStatus.NO_SIGNATURE) {
            throw new IllegalArgumentException(
                    "原訂單目前不是無人簽收狀態：" + sourceOrder.getStatus());
        }
        if (followUpOrder.getStatus() != OrderStatus.PENDING_CONFIRM) {
            throw new IllegalArgumentException(
                    "隔日補送單已進入後續流程，不能自動恢復：" + followUpOrder.getStatus());
        }
        if (sourceOrder.getRouteId() == null) {
            throw new IllegalArgumentException("原訂單已失去路線關聯，不能自動恢復");
        }

        RoutesEntity route = routesDAO.findForUpdate(sourceOrder.getRouteId())
                .orElseThrow(() -> new EntityNotFoundException(
                        "找不到原配送路線，ID：" + sourceOrder.getRouteId()));
        if (route.getStatus() != RouteStatus.PUBLISHED) {
            throw new IllegalArgumentException("原配送路線已撤回，不能自動恢復");
        }
        if (!route.getDate().equals(sourceOrder.getDeliveryDate())
                || !route.getDate().equals(now.toLocalDate())) {
            throw new IllegalArgumentException("只能在原配送當日恢復原單");
        }
        if (route.getDriverId() == null
                || sourceOrder.getAssignedDriverId() == null
                || !route.getDriverId().equals(sourceOrder.getAssignedDriverId())) {
            throw new IllegalArgumentException("原司機指派資料不完整或已變更，不能自動恢復");
        }
        if (sourceOrder.getAssignedVehicleId() == null
                || !route.getVehicleId().equals(sourceOrder.getAssignedVehicleId())
                || sourceOrder.getSequence() == null) {
            throw new IllegalArgumentException("原車輛或配送順序資料不完整，不能自動恢復");
        }

        DeliveryRecordsEntity latestRecord = deliveryRecordsDAO
                .findFirstByOrderIdOrderByIdDesc(sourceOrder.getId())
                .orElseThrow(() -> new IllegalArgumentException("找不到原無人簽收紀錄"));
        if (!latestRecord.getId().equals(exceptionCase.getDeliveryRecordId())
                || !Boolean.TRUE.equals(latestRecord.getNoSignature())) {
            throw new IllegalArgumentException("配送紀錄已被後續流程修改，不能重複恢復");
        }

        sourceOrder.setStatus(OrderStatus.IN_DELIVERY);
        ordersDAO.save(sourceOrder);

        followUpOrder.setStatus(OrderStatus.CANCELLED);
        ordersDAO.save(followUpOrder);

        DeliveryRecordsEntity resumedRecord = new DeliveryRecordsEntity();
        resumedRecord.setOrderId(sourceOrder.getId());
        resumedRecord.setArrivedAt(now);
        resumedRecord.setExpectedBoxCount(sourceOrder.getBoxCount());
        resumedRecord.setShortageBoxCount(0);
        resumedRecord.setDamagedBoxCount(0);
        resumedRecord.setReplacementRequiredBoxCount(0);
        resumedRecord.setNoSignature(false);
        deliveryRecordsDAO.save(resumedRecord);

        exceptionCase.setStatus(ExceptionStatus.CLOSED);
        exceptionCase.setHandledBy(reviewedBy);
        exceptionCase.setHandledAt(now);
        exceptionCase.setResolution("門市人員已返回，撤銷無人簽收並恢復原單配送");
        exceptionCasesDAO.save(exceptionCase);
        return deliveryExceptionService.toResponse(exceptionCase);
    }
}
