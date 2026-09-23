package com.example.backend.dao;

import com.example.backend.entity.DriversEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;

@Repository
public interface DriversDAO extends JpaRepository<DriversEntity, Long> {

    /**
     * 檢查司機帳號是否已存在
     * 用於 Service 層新增/修改時的唯一值防呆
     */
    boolean existsByAccount(String account);

    boolean existsByAccountIgnoreCase(String account);

    Optional<DriversEntity> findByAccount(String account);

    List<DriversEntity> findAllByIsActiveTrueOrderByIdAsc();
}
