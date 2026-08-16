package com.example.backend.dao;

import com.example.backend.entity.VehiclesEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VehiclesDAO extends JpaRepository<VehiclesEntity, Long> {

    /**
     * 檢查車牌號碼是否已存在
     * 用於 Service 層新增/修改時的唯一值防呆
     */
    boolean existsByPlateNumber(String plateNumber);
}