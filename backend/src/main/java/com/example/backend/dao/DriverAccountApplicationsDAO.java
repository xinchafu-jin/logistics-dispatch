package com.example.backend.dao;

import com.example.backend.constants.DriverApplicationStatus;
import com.example.backend.entity.DriverAccountApplicationsEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DriverAccountApplicationsDAO
        extends JpaRepository<DriverAccountApplicationsEntity, Long> {

    Optional<DriverAccountApplicationsEntity> findByAccountIgnoreCase(String account);

    List<DriverAccountApplicationsEntity> findByStatusOrderByAppliedAtAsc(
            DriverApplicationStatus status);

    long countByStatus(DriverApplicationStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from DriverAccountApplicationsEntity item where item.id = :id")
    Optional<DriverAccountApplicationsEntity> findForUpdate(@Param("id") Long id);
}
