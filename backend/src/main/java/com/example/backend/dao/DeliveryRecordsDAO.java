package com.example.backend.dao;

import com.example.backend.entity.DeliveryRecordsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;

@Repository
public interface DeliveryRecordsDAO extends JpaRepository<DeliveryRecordsEntity, Long> {

    /** 鎖住該訂單最近一次配送紀錄，避免同時完成交貨與無人簽收。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DeliveryRecordsEntity> findFirstByOrderIdOrderByIdDesc(Long orderId);
}
