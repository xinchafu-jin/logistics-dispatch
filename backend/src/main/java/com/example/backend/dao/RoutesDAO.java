package com.example.backend.dao;

import com.example.backend.entity.RoutesEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface RoutesDAO extends JpaRepository<RoutesEntity, Long> {
    List<RoutesEntity> findByDateAndWarehouseId(LocalDate date, Long warehouseId);
}

