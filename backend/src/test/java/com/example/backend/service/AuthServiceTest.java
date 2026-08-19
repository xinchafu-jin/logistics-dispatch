package com.example.backend.service;

import com.example.backend.dao.AdminUsersDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dto.request.LoginRequest;
import com.example.backend.dto.respones.LoginResponse;
import com.example.backend.entity.AdminUsersEntity;
import com.example.backend.entity.DriversEntity;
import com.example.backend.excition.LoginFailedException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AdminUsersDAO adminUsersDAO;
    @Mock
    private DriversDAO driversDAO;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtEncoder jwtEncoder;
    @Mock
    private Jwt encodedJwt;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(adminUsersDAO, driversDAO, passwordEncoder, jwtEncoder, 60);
    }

    @Test
    void managerCanLoginWithCorrectCredentials() {
        AdminUsersEntity admin = new AdminUsersEntity();
        admin.setId(1L);
        admin.setAccount("manager");
        admin.setPassword("hashed");
        admin.setName("物流主管");
        when(adminUsersDAO.findByAccount("manager")).thenReturn(Optional.of(admin));
        when(passwordEncoder.matches("Manager123!", "hashed")).thenReturn(true);
        when(jwtEncoder.encode(any())).thenReturn(encodedJwt);
        when(encodedJwt.getTokenValue()).thenReturn("jwt-token");

        LoginResponse response = authService.loginAdmin(login("manager", "Manager123!"));

        assertEquals(AuthService.ROLE_ADMIN, response.role());
        assertEquals("jwt-token", response.accessToken());
        assertEquals(1L, response.userId());
    }

    @Test
    void inactiveDriverCannotLogin() {
        DriversEntity driver = new DriversEntity();
        driver.setAccount("D001");
        driver.setIsActive(false);
        when(driversDAO.findByAccount("D001")).thenReturn(Optional.of(driver));

        LoginFailedException exception = assertThrows(
                LoginFailedException.class,
                () -> authService.loginDriver(login("D001", "Driver123!"))
        );

        assertEquals("此帳號目前無法登入，請聯絡物流主管", exception.getMessage());
        verify(jwtEncoder, never()).encode(any());
    }

    @Test
    void wrongPasswordReturnsGenericMessage() {
        when(adminUsersDAO.findByAccount("manager")).thenReturn(Optional.empty());

        LoginFailedException exception = assertThrows(
                LoginFailedException.class,
                () -> authService.loginAdmin(login("manager", "wrong-password"))
        );

        assertEquals("帳號或密碼錯誤", exception.getMessage());
    }

    private LoginRequest login(String account, String password) {
        LoginRequest request = new LoginRequest();
        request.setAccount(account);
        request.setPassword(password);
        return request;
    }
}
