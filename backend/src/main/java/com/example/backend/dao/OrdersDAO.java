package com.example.backend.dao;

import com.example.backend.constants.OrderStatus;
import com.example.backend.entity.OrdersEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

@Repository
public interface OrdersDAO extends JpaRepository<OrdersEntity, Long> {

    /**
     * 檢查訂單編號是否已存在
     * 用於 Service 層新增/修改時的唯一值防呆
     */
    boolean existsByOrderNumber(String orderNumber);

    /**
     * 排程用：撈某天、某狀態的訂單
     */
    List<OrdersEntity> findByDeliveryDateAndStatusAndWarehouseId(
            LocalDate deliveryDate, OrderStatus status, Long warehouseId);

    List<OrdersEntity> findByRouteIdOrderBySequence(Long routeId);

    List<OrdersEntity> findByDeliveryDateAndWarehouseIdAndRouteIdIsNull(LocalDate date, Long warehouseId);

    /**
     * 拖曳改派用：撈某天某倉的全部訂單，不分狀態、不管有沒有排進路線。
     * 用來驗證前端送來的 orderId 確實屬於這天這個倉。
     */
    List<OrdersEntity> findByDeliveryDateAndWarehouseId(LocalDate deliveryDate, Long warehouseId);

    List<OrdersEntity> findByRouteIdIn(List<Long> routeIds);
}