package com.example.backend.dao;

import com.example.backend.entity.RoutesEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface RoutesDAO extends JpaRepository<RoutesEntity, Long> {
    List<RoutesEntity> findByDateAndWarehouseId(LocalDate date, Long warehouseId);

    /**
     * 當天已指派司機的全部路線，不分倉庫。
     *
     * 司機不綁倉庫（見 docs/data-model.md），但一位司機一天只開一條路線，
     * 所以「誰還能指派」必須看整天而不是只看當前倉。
     */
    List<RoutesEntity> findByDateAndDriverIdIsNotNull(LocalDate date);
}

