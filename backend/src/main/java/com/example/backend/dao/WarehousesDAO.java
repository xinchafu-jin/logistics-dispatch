package com.example.backend.dao;

import com.example.backend.entity.WarehousesEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WarehousesDAO extends JpaRepository<WarehousesEntity, Long> {

    boolean existsByWarehouseCode(String warehouseCode);
}