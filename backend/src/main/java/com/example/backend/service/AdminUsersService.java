package com.example.backend.service;

import com.example.backend.dao.AdminUsersDAO;
import com.example.backend.dto.request.AdminPasswordResetDTO;
import com.example.backend.dto.request.AdminPasswordResetVerificationDTO;
import com.example.backend.dto.request.AdminUsersDTO;
import com.example.backend.entity.AdminUsersEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AdminUsersService {

    private final AdminUsersDAO adminUsersDAO;
    private final PasswordEncoder passwordEncoder;

    public AdminUsersService(AdminUsersDAO adminUsersDAO, PasswordEncoder passwordEncoder) {
        this.adminUsersDAO = adminUsersDAO;
        this.passwordEncoder = passwordEncoder;
    }

    public AdminUsersDTO create(AdminUsersDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("主管資料不可空白");
        }
        validateRequired(dto.getAccount(), "主管帳號不可空白");
        validateRequired(dto.getName(), "主管姓名不可空白");
        validateRequired(dto.getPhone(), "主管手機號碼不可空白");
        validatePassword(dto.getPassword());

        String account = dto.getAccount().trim();
        if (adminUsersDAO.existsByAccount(account)) {
            throw new IllegalArgumentException("主管帳號已存在：" + account);
        }

        AdminUsersEntity entity = new AdminUsersEntity();
        entity.setAccount(account);
        entity.setPassword(passwordEncoder.encode(dto.getPassword()));
        entity.setName(dto.getName().trim());
        entity.setPhone(dto.getPhone().trim());
        return toDTO(adminUsersDAO.save(entity));
    }

    @Transactional(readOnly = true)
    public void verifyPasswordResetIdentity(AdminPasswordResetVerificationDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("帳號或手機號碼不正確");
        }
        findAdminForPasswordReset(dto.getAccount(), dto.getPhone());
    }

    public void resetForgottenPassword(AdminPasswordResetDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("帳號或手機號碼不正確");
        }
        validatePassword(dto.getNewPassword());
        AdminUsersEntity entity = findAdminForPasswordReset(dto.getAccount(), dto.getPhone());
        entity.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        adminUsersDAO.save(entity);
    }

    private AdminUsersEntity findAdminForPasswordReset(String account, String phone) {
        if (account == null || account.isBlank() || phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("帳號或手機號碼不正確");
        }

        AdminUsersEntity entity = adminUsersDAO.findByAccount(account.trim())
                .orElseThrow(() -> new IllegalArgumentException("帳號或手機號碼不正確"));
        if (!phone.trim().equals(entity.getPhone())) {
            throw new IllegalArgumentException("帳號或手機號碼不正確");
        }
        return entity;
    }

    private void validateRequired(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("密碼不可空白");
        }
        if (password.length() < 8 || password.length() > 12) {
            throw new IllegalArgumentException("密碼長度必須介於 8 到 12 字元");
        }
    }

    private AdminUsersDTO toDTO(AdminUsersEntity entity) {
        AdminUsersDTO dto = new AdminUsersDTO();
        dto.setId(entity.getId());
        dto.setAccount(entity.getAccount());
        dto.setName(entity.getName());
        dto.setPhone(entity.getPhone());
        return dto;
    }
}
