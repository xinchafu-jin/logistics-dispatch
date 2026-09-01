package com.example.backend.dao;

import com.example.backend.entity.TemplateRoutesEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TemplateRoutesDAO extends JpaRepository<TemplateRoutesEntity, Long> {

    List<TemplateRoutesEntity> findByTemplateIdOrderByIdAsc(Long templateId);
}
