package com.example.backend.dao;

import com.example.backend.entity.ExceptionCasesEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ExceptionCasesDAO extends JpaRepository<ExceptionCasesEntity, Long> {
}
