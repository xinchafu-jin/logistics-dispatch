package com.example.backend.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "admin_users")
public class AdminUsersEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false,unique = true,length = 60)
    private String account;

    /** BCrypt 雜湊，不儲存明碼。 */
    @Column(nullable = false, length = 60)
    private String password;
    @Column(nullable = false,length = 60)
    private String name;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
