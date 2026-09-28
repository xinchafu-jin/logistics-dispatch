package com.example.backend.dao;

import com.example.backend.constants.ExceptionStatus;
import com.example.backend.constants.ExceptionType;
import com.example.backend.entity.ExceptionCasesEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface ExceptionCasesDAO extends JpaRepository<ExceptionCasesEntity, Long> {

    List<ExceptionCasesEntity> findByStatusAndQueuedAtIsNotNullOrderByQueuedAtAsc(
            ExceptionStatus status
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from ExceptionCasesEntity item where item.id = :id")
    Optional<ExceptionCasesEntity> findForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from ExceptionCasesEntity item "
            + "where item.status = :status "
            + "and item.reviewAvailableAt is not null "
            + "and item.reviewAvailableAt <= :now "
            + "and item.queuedAt is null "
            + "order by item.reviewAvailableAt asc, item.id asc")
    List<ExceptionCasesEntity> findDueForUpdate(
            @Param("status") ExceptionStatus status,
            @Param("now") LocalDateTime now
    );

    // ── 司機例外回報（type = DRIVER_REPORT），索引見 V14 ──

    /** 司機端「我的案件」：這位司機某個狀態的回報，新的在前 */
    List<ExceptionCasesEntity> findByDriverIdAndTypeAndStatusOrderByIdDesc(
            Long driverId, ExceptionType type, ExceptionStatus status);

    /** 司機端的已結案只列最近 20 件；進行中的才是當天要處理的，一律全部列 */
    List<ExceptionCasesEntity> findTop20ByDriverIdAndTypeAndStatusOrderByIdDesc(
            Long driverId, ExceptionType type, ExceptionStatus status);

    /** 異常中心進行中的司機回報：全部列出，誰排前面由 DriverCaseService 決定 */
    List<ExceptionCasesEntity> findByTypeAndStatusOrderByIdAsc(ExceptionType type, ExceptionStatus status);

    /** 異常中心已結案的司機回報：最近結案的 50 件 */
    List<ExceptionCasesEntity> findTop50ByTypeAndStatusOrderByHandledAtDescIdDesc(
            ExceptionType type, ExceptionStatus status);
}
