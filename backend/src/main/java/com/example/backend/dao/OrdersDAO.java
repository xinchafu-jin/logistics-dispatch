package com.example.backend.dao;

import com.example.backend.entity.OrdersEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface OrdersDAO extends JpaRepository<OrdersEntity, Long> {

    /**
     * 檢查訂單編號是否已存在
     * 用於 Service 層新增/修改時的唯一值防呆
     */
    boolean existsByOrderNumber(String orderNumber);
}