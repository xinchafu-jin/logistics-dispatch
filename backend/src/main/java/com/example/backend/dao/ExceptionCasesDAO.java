package com.example.backend.dao;

import com.example.backend.constants.ExceptionStatus;
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
}
