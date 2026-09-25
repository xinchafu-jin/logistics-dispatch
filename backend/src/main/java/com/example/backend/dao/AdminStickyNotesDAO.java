package com.example.backend.dao;

import com.example.backend.entity.AdminStickyNotesEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AdminStickyNotesDAO extends JpaRepository<AdminStickyNotesEntity, Long> {
    List<AdminStickyNotesEntity> findByAdminIdOrderBySortOrderAscUpdatedAtDesc(Long adminId);
    Optional<AdminStickyNotesEntity> findByIdAndAdminId(Long id, Long adminId);
}
