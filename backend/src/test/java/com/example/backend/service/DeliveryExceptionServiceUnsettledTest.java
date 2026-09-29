package com.example.backend.service;

import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.constants.OrderStatus;
import com.example.backend.constants.OrderType;
import com.example.backend.dao.DeliveryRecordsDAO;
import com.example.backend.dao.ExceptionCasesDAO;
import com.example.backend.dao.OrdersDAO;
import com.example.backend.dao.RoutesDAO;
import com.example.backend.entity.ExceptionCasesEntity;
import com.example.backend.entity.OrderItemsEntity;
import com.example.backend.entity.OrdersEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeliveryExceptionServiceUnsettledTest {
    @Test
    void overdueUnstartedOrderCreatesOneCaseAndFollowUpThenSupervisorMovesItToToday() {
        LocalDate today = LocalDate.of(2026, 9, 30);
        LocalDateTime now = today.atTime(0, 5);
        OrdersDAO orders = mock(OrdersDAO.class);
        ExceptionCasesDAO cases = mock(ExceptionCasesDAO.class);
        DispatchBoardPushService pushes = mock(DispatchBoardPushService.class);
        DeliveryExceptionService service = new DeliveryExceptionService(cases,
                mock(DeliveryRecordsDAO.class), orders, mock(RoutesDAO.class), pushes);
        OrdersEntity source = new OrdersEntity();
        source.setId(9L);
        source.setOrderNumber("DO-20260929-053");
        source.setDeliveryDate(today.minusDays(1));
        source.setStatus(OrderStatus.CONFIRMED);
        source.setStoreId(4L);
        source.setWarehouseId(1L);
        source.setBoxCount(4);
        source.setRetryCount(0);
        OrderItemsEntity item = new OrderItemsEntity();
        item.setProductCode("P-1");
        item.setItemName("飲用水");
        item.setExpectedQuantity(4);
        item.setSequence(1);
        source.addItem(item);
        when(orders.findOverdueUnsettledIds(today,
                List.of(OrderStatus.PENDING_CONFIRM, OrderStatus.CONFIRMED),
                ExceptionType.UNSETTLED_ORDER, ExceptionStatus.OPEN)).thenReturn(List.of(9L));
        when(orders.findForUpdate(9L)).thenReturn(Optional.of(source));
        when(cases.existsByOrderIdAndType(9L, ExceptionType.UNSETTLED_ORDER)).thenReturn(false, true);
        when(orders.save(any(OrdersEntity.class))).thenAnswer(invocation -> {
            OrdersEntity saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(50L);
            return saved;
        });
        when(cases.save(any(ExceptionCasesEntity.class))).thenAnswer(invocation -> {
            ExceptionCasesEntity saved = invocation.getArgument(0);
            if (saved.getId() == null) saved.setId(5L);
            return saved;
        });

        service.queueDueCases(now);
        service.queueDueCases(now.plusMinutes(1));

        var orderCaptor = org.mockito.ArgumentCaptor.forClass(OrdersEntity.class);
        verify(orders, times(1)).save(orderCaptor.capture());
        OrdersEntity followUp = orderCaptor.getValue();
        assertEquals(OrderStatus.PENDING_CONFIRM, followUp.getStatus());
        assertEquals(OrderType.REDELIVERY, followUp.getOrderType());
        assertEquals(today.plusDays(1), followUp.getDeliveryDate());
        assertEquals(source.getId(), followUp.getParentOrderId());
        assertEquals(4, followUp.getItems().getFirst().getExpectedQuantity());
        assertNull(followUp.getItems().getFirst().getCheckedAt());
        assertEquals(OrderStatus.CONFIRMED, source.getStatus());
        var caseCaptor = org.mockito.ArgumentCaptor.forClass(ExceptionCasesEntity.class);
        verify(cases, times(1)).save(caseCaptor.capture());
        ExceptionCasesEntity incident = caseCaptor.getValue();
        assertEquals(ExceptionType.UNSETTLED_ORDER, incident.getType());
        assertEquals(source.getId(), incident.getOrderId());
        assertEquals(followUp.getId(), incident.getFollowUpOrderId());
        assertEquals(ExceptionStatus.OPEN, incident.getStatus());

        when(cases.findForUpdate(5L)).thenReturn(Optional.of(incident));
        when(orders.findForUpdate(50L)).thenReturn(Optional.of(followUp));
        when(orders.findById(9L)).thenReturn(Optional.of(source));
        when(orders.findById(50L)).thenReturn(Optional.of(followUp));
        service.confirm(5L, "主管");

        assertEquals(OrderStatus.FAILED, source.getStatus());
        assertEquals(today.minusDays(1), source.getDeliveryDate());
        assertEquals(today, followUp.getDeliveryDate());
        assertEquals(OrderStatus.CONFIRMED, followUp.getStatus());
        assertEquals(ExceptionStatus.CLOSED, incident.getStatus());
        verify(pushes, atLeastOnce()).markChanged(today.minusDays(1));
        assertTrue(followUp.getOrderNumber().startsWith("UN-"));
    }
}
