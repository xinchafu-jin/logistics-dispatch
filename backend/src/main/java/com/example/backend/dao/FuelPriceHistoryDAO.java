package com.example.backend.dao;

import com.example.backend.constants.FuelType;
import com.example.backend.entity.FuelPriceHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface FuelPriceHistoryDAO extends JpaRepository<FuelPriceHistoryEntity, Long> {

    Optional<FuelPriceHistoryEntity> findByFuelTypeAndEffectiveFrom(
            FuelType fuelType,
            LocalDateTime effectiveFrom
    );

    Optional<FuelPriceHistoryEntity>
    findFirstByFuelTypeAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(
            FuelType fuelType,
            LocalDateTime effectiveAt
    );

    @Query("select fuelPrice from FuelPriceHistoryEntity fuelPrice "
            + "where fuelPrice.fuelType = :fuelType "
            + "and (:fromTime is null or fuelPrice.effectiveFrom >= :fromTime) "
            + "and (:toTime is null or fuelPrice.effectiveFrom < :toTime) "
            + "order by fuelPrice.effectiveFrom desc")
    List<FuelPriceHistoryEntity> findHistory(
            @Param("fuelType") FuelType fuelType,
            @Param("fromTime") LocalDateTime fromTime,
            @Param("toTime") LocalDateTime toTime
    );
}
