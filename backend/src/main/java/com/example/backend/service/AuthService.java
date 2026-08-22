package com.example.backend.service;

import com.example.backend.dao.AdminUsersDAO;
import com.example.backend.dao.DriversDAO;
import com.example.backend.dto.request.LoginRequest;
import com.example.backend.dto.respones.LoginResponse;
import com.example.backend.entity.AdminUsersEntity;
import com.example.backend.entity.DriversEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@Transactional(readOnly = true)
public class AuthService {

    public static final String TOKEN_ISSUER = "logistics-dispatch";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_DRIVER = "DRIVER";

    private final AdminUsersDAO adminUsersDAO;
    private final DriversDAO driversDAO;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final long expirationMinutes;

    public AuthService(
            AdminUsersDAO adminUsersDAO,
            DriversDAO driversDAO,
            PasswordEncoder passwordEncoder,
            JwtEncoder jwtEncoder,
            @Value("${app.jwt.expiration-minutes:480}") long expirationMinutes
    ) {
        this.adminUsersDAO = adminUsersDAO;
        this.driversDAO = driversDAO;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.expirationMinutes = expirationMinutes;
    }

    public LoginResponse loginAdmin(LoginRequest request) {
        AdminUsersEntity admin = adminUsersDAO.findByAccount(request.getAccount())
                .orElseThrow(this::invalidCredentials);

        if (!passwordEncoder.matches(request.getPassword(), admin.getPassword())) {
            throw invalidCredentials();
        }

        return issueToken(admin.getId(), admin.getAccount(), admin.getName(), ROLE_ADMIN);
    }

    public LoginResponse loginDriver(LoginRequest request) {
        DriversEntity driver = driversDAO.findByAccount(request.getAccount())
                .orElseThrow(this::invalidCredentials);

        if (!Boolean.TRUE.equals(driver.getIsActive())) {
            throw new IllegalArgumentException("此帳號目前無法登入，請聯絡物流主管");
        }
        if (driver.getPassword() == null
                || !passwordEncoder.matches(request.getPassword(), driver.getPassword())) {
            throw invalidCredentials();
        }

        return issueToken(driver.getId(), driver.getAccount(), driver.getName(), ROLE_DRIVER);
    }

    private LoginResponse issueToken(Long userId, String account, String name, String role) {
        Instant issuedAt = Instant.now();
        Instant expiresAt = issuedAt.plus(expirationMinutes, ChronoUnit.MINUTES);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(TOKEN_ISSUER)
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .subject(account)
                .claim("userId", userId)
                .claim("name", name)
                .claim("role", role)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();

        return new LoginResponse(token, "Bearer", expiresAt, role, userId, account, name);
    }

    private IllegalArgumentException invalidCredentials() {
        return new IllegalArgumentException("帳號或密碼錯誤");
    }
}
