package com.example.backend.dao;

import com.example.backend.entity.AdminUsersEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AdminUsersDAO extends JpaRepository<AdminUsersEntity, Long> {
    Optional<AdminUsersEntity> findByAccount(String account);
}
