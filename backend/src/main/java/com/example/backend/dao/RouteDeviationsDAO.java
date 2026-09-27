package com.example.backend.dao;

import com.example.backend.entity.RouteDeviationsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface RouteDeviationsDAO extends JpaRepository<RouteDeviationsEntity, Long> {

    /** 進行中的偏離，先開始的在前：後台警報清單、每分鐘的升級排程都用這個 */
    List<RouteDeviationsEntity> findAllByEndedAtIsNullOrderByStartedAtAsc();

    /**
     * 這位司機進行中的那一筆：要結束它、或後端重啟後還原「偏離中」時用。
     * 照理一位司機同時只會有一筆；萬一有兩筆，拿最新的。
     */
    Optional<RouteDeviationsEntity> findFirstByDriverIdAndEndedAtIsNullOrderByStartedAtDesc(Long driverId);
}
