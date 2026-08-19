package com.example.backend.config;

import com.example.backend.dao.AdminUsersDAO;
import com.example.backend.entity.AdminUsersEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AdminBootstrap implements ApplicationRunner {

    private final AdminUsersDAO adminUsersDAO;
    private final PasswordEncoder passwordEncoder;
    private final String account;
    private final String password;
    private final String name;

    public AdminBootstrap(
            AdminUsersDAO adminUsersDAO,
            PasswordEncoder passwordEncoder,
            @Value("${app.bootstrap-admin.account:}") String account,
            @Value("${app.bootstrap-admin.password:}") String password,
            @Value("${app.bootstrap-admin.name:物流主管}") String name
    ) {
        this.adminUsersDAO = adminUsersDAO;
        this.passwordEncoder = passwordEncoder;
        this.account = account;
        this.password = password;
        this.name = name;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (adminUsersDAO.count() > 0 || account.isBlank() || password.isBlank()) {
            return;
        }
        if (password.length() < 8) {
            throw new IllegalStateException("初始主管密碼必須至少 8 字元");
        }

        AdminUsersEntity admin = new AdminUsersEntity();
        admin.setAccount(account);
        admin.setPassword(passwordEncoder.encode(password));
        admin.setName(name);
        adminUsersDAO.save(admin);
    }
}
