package com.example.backend.service;

import com.example.backend.dao.AdminUsersDAO;
import com.example.backend.dto.request.AdminPasswordChangeDTO;
import com.example.backend.dto.request.AdminPasswordResetDTO;
import com.example.backend.dto.request.AdminPasswordResetVerificationDTO;
import com.example.backend.dto.request.AdminUsersDTO;
import com.example.backend.dto.respones.AiApiKeyStatusResponse;
import com.example.backend.entity.AdminUsersEntity;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@Transactional
public class AdminUsersService {

    private final AdminUsersDAO adminUsersDAO;
    private final PasswordEncoder passwordEncoder;
    private final TextEncryptor aiApiKeyEncryptor;

    public AdminUsersService(AdminUsersDAO adminUsersDAO,
                             PasswordEncoder passwordEncoder,
                             TextEncryptor aiApiKeyEncryptor) {
        this.adminUsersDAO = adminUsersDAO;
        this.passwordEncoder = passwordEncoder;
        this.aiApiKeyEncryptor = aiApiKeyEncryptor;
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

    /** 已登入主管使用原密碼修改自己的密碼。主管身分由 JWT 的 userId 決定。 */
    public void changePassword(Long userId, AdminPasswordChangeDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("密碼資料不可空白");
        }

        validateRequired(dto.getCurrentPassword(), "原密碼不可空白");
        validatePassword(dto.getNewPassword());

        AdminUsersEntity entity = findAdmin(userId);
        if (!passwordEncoder.matches(dto.getCurrentPassword(), entity.getPassword())) {
            throw new IllegalArgumentException("原密碼不正確");
        }
        if (passwordEncoder.matches(dto.getNewPassword(), entity.getPassword())) {
            throw new IllegalArgumentException("新密碼不可與原密碼相同");
        }

        entity.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        adminUsersDAO.save(entity);
    }

    /** 查詢 Key 設定狀態。不解密，密文解不開時狀態頁仍打得開，使用者才進得去重新設定。 */
    @Transactional(readOnly = true)
    public AiApiKeyStatusResponse getAiApiKeyStatus(Long userId) {
        return toAiApiKeyStatus(findAdmin(userId));
    }

    /** 新增與修改共用：一人只有一把 Key，兩者都是加密後覆蓋舊的。 */
    public AiApiKeyStatusResponse saveAiApiKey(Long userId, String apiKey) {
        String trimmedKey = validateAiApiKey(apiKey);
        AdminUsersEntity entity = findAdmin(userId);
        entity.setAiApiKeyEncrypted(aiApiKeyEncryptor.encrypt(trimmedKey));
        entity.setAiApiKeyLast4(trimmedKey.substring(trimmedKey.length() - 4));
        entity.setAiApiKeyUpdatedAt(LocalDateTime.now());
        adminUsersDAO.save(entity);
        return toAiApiKeyStatus(entity);
    }

    public void deleteAiApiKey(Long userId) {
        AdminUsersEntity entity = findAdmin(userId);
        entity.setAiApiKeyEncrypted(null);
        entity.setAiApiKeyLast4(null);
        entity.setAiApiKeyUpdatedAt(null);
        adminUsersDAO.save(entity);
    }

    /**
     * 給 AI 助理用：回傳解密後的 Key，沒設定回 null。
     *
     * <p>換過 APP_CRYPTO_PASSWORD／SALT 後舊密文會解不開，Spring 丟的是 IllegalStateException，
     * 這裡轉成使用者看得懂的訊息。只接這一種，其他例外照常往外丟，避免把程式錯誤藏起來。</p>
     */
    @Transactional(readOnly = true)
    public String findAiApiKey(Long userId) {
        String encrypted = findAdmin(userId).getAiApiKeyEncrypted();
        if (encrypted == null) {
            return null;
        }
        try {
            return aiApiKeyEncryptor.decrypt(encrypted);
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException("AI API Key 無法讀取，請到個人資料重新設定");
        }
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

    private AdminUsersEntity findAdmin(Long userId) {
        return adminUsersDAO.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("找不到主管帳號"));
    }

    private String validateAiApiKey(String apiKey) {
        validateRequired(apiKey, "API Key 不可空白");
        String trimmedKey = apiKey.trim();
        if (trimmedKey.length() < 8 || trimmedKey.length() > 200) {
            throw new IllegalArgumentException("API Key 長度必須介於 8 到 200 字元");
        }
        if (trimmedKey.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalArgumentException("API Key 中間不可有空白或換行，請確認複製是否完整");
        }
        return trimmedKey;
    }

    private AiApiKeyStatusResponse toAiApiKeyStatus(AdminUsersEntity entity) {
        if (entity.getAiApiKeyEncrypted() == null) {
            return new AiApiKeyStatusResponse(false, null, null);
        }
        return new AiApiKeyStatusResponse(true, "****" + entity.getAiApiKeyLast4(), entity.getAiApiKeyUpdatedAt());
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
