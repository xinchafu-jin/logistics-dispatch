package com.example.backend.service;

import com.example.backend.dao.AdminUsersDAO;
import com.example.backend.dto.request.AdminProfileUpdateDTO;
import com.example.backend.dto.respones.AdminProfileResponse;
import com.example.backend.entity.AdminUsersEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.TextEncryptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 個人資料：改姓名不用密碼，改手機要附目前的密碼（手機是忘記密碼的驗證依據）。
 *
 * <p>主管 1 號，帳號 admin，手機 0912345678，密碼 correct-pw。</p>
 */
class AdminUsersServiceProfileTest {

    private static final long ADMIN_ID = 1L;

    private AdminUsersDAO adminUsersDAO;
    private AdminUsersEntity admin;
    private AdminUsersService service;

    @BeforeEach
    void setUp() {
        adminUsersDAO = mock(AdminUsersDAO.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(passwordEncoder.matches("correct-pw", "hashed")).thenReturn(true);
        admin = new AdminUsersEntity();
        admin.setId(ADMIN_ID);
        admin.setAccount("admin");
        admin.setName("系統管理員");
        admin.setPhone("0912345678");
        admin.setPassword("hashed");
        when(adminUsersDAO.findById(ADMIN_ID)).thenReturn(Optional.of(admin));
        when(adminUsersDAO.save(any(AdminUsersEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        service = new AdminUsersService(adminUsersDAO, passwordEncoder, mock(TextEncryptor.class));
    }

    @Test
    void 只改姓名_不用密碼_前後空白去掉() {
        AdminProfileResponse response = service.updateProfile(ADMIN_ID, update("  王主管  ", "0912345678", null));

        assertEquals("王主管", admin.getName());
        assertEquals("王主管", response.getName());
        assertEquals("admin", response.getAccount());
    }

    @Test
    void 改手機_沒附密碼_擋下也不存() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateProfile(ADMIN_ID, update("系統管理員", "0987654321", null)));

        assertEquals("修改手機號碼需要輸入目前的密碼", error.getMessage());
        assertEquals("0912345678", admin.getPhone());
        verify(adminUsersDAO, never()).save(any());
    }

    @Test
    void 改手機_密碼錯_擋下也不存() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.updateProfile(ADMIN_ID, update("系統管理員", "0987654321", "wrong-pw")));

        assertEquals("目前的密碼不正確", error.getMessage());
        assertEquals("0912345678", admin.getPhone());
        verify(adminUsersDAO, never()).save(any());
    }

    @Test
    void 改手機_密碼對_存起來() {
        service.updateProfile(ADMIN_ID, update("系統管理員", " 0987654321 ", "correct-pw"));

        assertEquals("0987654321", admin.getPhone(), "忘記密碼是逐字比對，前後空白要去掉");
    }

    @Test
    void 讀個人資料_姓名手機以資料庫為準() {
        AdminProfileResponse response = service.getProfile(ADMIN_ID);

        assertEquals("系統管理員", response.getName());
        assertEquals("0912345678", response.getPhone());
    }

    private AdminProfileUpdateDTO update(String name, String phone, String currentPassword) {
        AdminProfileUpdateDTO dto = new AdminProfileUpdateDTO();
        dto.setName(name);
        dto.setPhone(phone);
        dto.setCurrentPassword(currentPassword);
        return dto;
    }
}
