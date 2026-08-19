package com.example.backend.dao;

import com.example.backend.entity.DriversEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DriversDAO extends JpaRepository<DriversEntity, Long> {

    /**
     * 檢查司機帳號是否已存在
     * 用於 Service 層新增/修改時的唯一值防呆
     */
    boolean existsByAccount(String account);
}