package com.example.backend.dao;

import com.example.backend.entity.StoresEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StoresDAO extends JpaRepository<StoresEntity, Long> {

    /**
     * 檢查門市代碼是否已存在
     * 用於 Service 層新增/修改時的唯一值防呆
     */
    boolean existsByStoreCode(String storeCode);
}