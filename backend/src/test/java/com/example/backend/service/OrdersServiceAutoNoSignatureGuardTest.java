package com.example.backend.service;

import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.constants.OrderReviewAction;
import com.example.backend.constants.OrderStatus;
import com.example.backend.dao.ExceptionCasesDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.StoresDAO;
import com.example.backend.dao.WarehousesDAO;
import com.example.backend.dto.request.OrderReviewRequestDTO;
import com.example.backend.entity.ExceptionCasesEntity;
import com.example.backend.entity.OrdersEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OrdersServiceAutoNoSignatureGuardTest {
    private OrdersDAO orders;
    private ExceptionCasesDAO cases;
    private OrdersService service;

    @BeforeEach
    void setUp() {
        orders = mock(OrdersDAO.class);
        cases = mock(ExceptionCasesDAO.class);
        service = new OrdersService(orders, mock(StoresDAO.class),
                mock(WarehousesDAO.class), cases);
    }

    @Test
    void 無人簽收重送單不能從一般確認入口搶先送待排() {
        when(cases.existsByFollowUpOrderIdAndTypeAndStatus(
                2L, ExceptionType.NO_SIGNATURE, ExceptionStatus.OPEN)).thenReturn(true);
        OrderReviewRequestDTO request = new OrderReviewRequestDTO();
        request.setAction(OrderReviewAction.CONFIRM);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.review(2L, request));

        assertTrue(error.getMessage().contains("06:00"));
        verify(orders, never()).findForUpdate(any());
    }

    @Test
    void 無人簽收待自動送單不能由一般編輯或刪除繞過排程() {
        when(cases.existsByFollowUpOrderIdAndTypeAndStatus(
                2L, ExceptionType.NO_SIGNATURE, ExceptionStatus.OPEN)).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () -> service.update(2L, null));
        assertThrows(IllegalArgumentException.class, () -> service.delete(2L));
        verify(orders, never()).findById(any());
    }

    @Test
    void 一般待確認訂單仍可由主管確認() {
        OrdersEntity normalOrder = new OrdersEntity();
        normalOrder.setId(3L);
        normalOrder.setOrderNumber("DO-3");
        normalOrder.setStatus(OrderStatus.PENDING_CONFIRM);
        when(orders.findForUpdate(3L)).thenReturn(Optional.of(normalOrder));
        when(orders.save(normalOrder)).thenReturn(normalOrder);
        OrderReviewRequestDTO request = new OrderReviewRequestDTO();
        request.setAction(OrderReviewAction.CONFIRM);

        service.review(3L, request);

        assertEquals(OrderStatus.CONFIRMED, normalOrder.getStatus());
        verify(orders).save(normalOrder);
    }

    @Test
    void 未結與點交異常後續單只能在異常中心確認() {
        when(cases.existsByFollowUpOrderIdAndStatus(4L, ExceptionStatus.OPEN)).thenReturn(true);
        OrderReviewRequestDTO request = new OrderReviewRequestDTO();
        request.setAction(OrderReviewAction.CONFIRM);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.review(4L, request));

        assertTrue(error.getMessage().contains("異常中心"));
        verify(orders, never()).findForUpdate(any());
    }

    @Test
    void 審單清單標示無人簽收等待系統自動送單() {
        OrdersEntity followUp = new OrdersEntity();
        followUp.setId(2L);
        followUp.setOrderNumber("NS-2");
        ExceptionCasesEntity incident = new ExceptionCasesEntity();
        incident.setFollowUpOrderId(2L);
        when(orders.findAll()).thenReturn(List.of(followUp));
        when(cases.findByTypeAndStatusOrderByIdAsc(
                ExceptionType.NO_SIGNATURE, ExceptionStatus.OPEN)).thenReturn(List.of(incident));

        var result = service.findAll();

        assertTrue(result.getFirst().isAwaitingAutomaticDispatch());
    }

    @Test
    void 審單清單標示未結異常等待主管從異常中心確認() {
        OrdersEntity followUp = new OrdersEntity();
        followUp.setId(4L);
        followUp.setOrderNumber("UN-4");
        ExceptionCasesEntity incident = new ExceptionCasesEntity();
        incident.setType(ExceptionType.UNSETTLED_ORDER);
        incident.setFollowUpOrderId(4L);
        when(orders.findAll()).thenReturn(List.of(followUp));
        when(cases.findByStatus(ExceptionStatus.OPEN))
                .thenReturn(List.of(incident));

        var result = service.findAll();

        assertTrue(result.getFirst().isAwaitingExceptionReview());
    }
}
