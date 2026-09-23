package com.example.backend.dao;

import com.example.backend.constants.RouteStatus;
import com.example.backend.entity.RoutesEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface RoutesDAO extends JpaRepository<RoutesEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select route from RoutesEntity route where route.id = :id")
    Optional<RoutesEntity> findForUpdate(@Param("id") Long id);

    List<RoutesEntity> findByDateAndWarehouseId(LocalDate date, Long warehouseId);

    /**
     * 當天全部倉庫的路線。
     *
     * 發布與撤回是一次涵蓋所有倉庫的動作，不像排車是一次一倉
     * 所以這裡不帶 warehouseId。
     */
    List<RoutesEntity> findByDate(LocalDate date);

    /**
     * 當天已指派司機的全部路線，不分倉庫。
     *
     * 司機不綁倉庫（見 docs/data-model.md），但一位司機一天只開一條路線，
     * 所以「誰還能指派」必須看整天而不是只看當前倉。
     */
    List<RoutesEntity> findByDateAndDriverIdIsNotNull(LocalDate date);

    /** 司機端只讀取已正式發布給本人的路線。 */
    List<RoutesEntity> findByDateAndDriverIdAndStatusOrderByIdAsc(
            LocalDate date, Long driverId, RouteStatus status);

    boolean existsByDateAndDriverIdAndStatus(
            LocalDate date, Long driverId, RouteStatus status);

}

