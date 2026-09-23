package com.example.backend.service;

import com.example.backend.dao.DriversDAO;
import com.example.backend.dto.request.DriverPasswordResetDTO;
import com.example.backend.dto.request.DriverPasswordResetVerificationDTO;
import com.example.backend.dto.request.DriversDTO;
import com.example.backend.entity.DriversEntity;
import jakarta.persistence.EntityNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Service
@Transactional
public class DriversService {

    private static final int[] NATIONAL_ID_LETTER_CODES = {
            10, 11, 12, 13, 14, 15, 16, 17, 34,
            18, 19, 20, 21, 22, 35, 23, 24, 25,
            26, 27, 28, 29, 32, 30, 31, 33
    };

    private final DriversDAO driversDAO;
    private final PasswordEncoder passwordEncoder;
    private final DriverPhotoStorageService driverPhotoStorageService;

    public DriversService(
            DriversDAO driversDAO,
            PasswordEncoder passwordEncoder,
            DriverPhotoStorageService driverPhotoStorageService
    ) {
        this.driversDAO = driversDAO;
        this.passwordEncoder = passwordEncoder;
        this.driverPhotoStorageService = driverPhotoStorageService;
    }

    @Transactional(readOnly = true)
    public List<DriversDTO> findAll() {
        return driversDAO.findAll().stream().map(this::toDTO).toList();
    }

    @Transactional(readOnly = true)
    public DriversDTO findById(Long id) {
        return toDTO(findEntity(id));
    }

    public DriversDTO create(DriversDTO dto) {
        if (driversDAO.existsByAccount(dto.getAccount())) {
            throw new IllegalArgumentException("司機帳號已存在：" + dto.getAccount());
        }
        validateInitialNationalIdPassword(dto.getPassword());
        DriversEntity entity = new DriversEntity();
        apply(dto, entity);
        entity.setPassword(passwordEncoder.encode(dto.getPassword()));
        return toDTO(driversDAO.save(entity));
    }

    public List<DriversDTO> createAll(List<DriversDTO> dtos) {
        List<DriversEntity> entities = dtos.stream().map(dto -> {
            if (driversDAO.existsByAccount(dto.getAccount())) {
                throw new IllegalArgumentException("司機帳號已存在：" + dto.getAccount());
            }
            validateInitialNationalIdPassword(dto.getPassword());
            DriversEntity entity = new DriversEntity();
            apply(dto, entity);
            entity.setPassword(passwordEncoder.encode(dto.getPassword()));
            return entity;
        }).toList();
        return driversDAO.saveAll(entities).stream().map(this::toDTO).toList();
    }

    public DriversDTO update(Long id, DriversDTO dto) {
        DriversEntity entity = findEntity(id);
        if (!entity.getAccount().equals(dto.getAccount()) && driversDAO.existsByAccount(dto.getAccount())) {
            throw new IllegalArgumentException("司機帳號已存在：" + dto.getAccount());
        }
        apply(dto, entity);
        if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
            validatePassword(dto.getPassword(), "新密碼不可空白");
            entity.setPassword(passwordEncoder.encode(dto.getPassword()));
        }
        return toDTO(driversDAO.save(entity));
    }

    @Transactional(readOnly = true)
    public DriversDTO findByAccount(String account) {
        DriversEntity entity = driversDAO.findByAccount(account.trim())
                .orElseThrow(() -> new IllegalArgumentException("帳號或手機號碼不正確"));
        return toDTO(entity);
    }

    @Transactional(readOnly = true)
    public void verifyPasswordResetIdentity(DriverPasswordResetVerificationDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("帳號或手機號碼不正確");
        }
        findDriverForPasswordReset(dto.getAccount(), dto.getPhone());
    }

    public void resetForgottenPassword(DriverPasswordResetDTO dto) {
        if (dto == null) {
            throw new IllegalArgumentException("帳號或手機號碼不正確");
        }
        validatePassword(dto.getNewPassword(), "新密碼不可空白");
        DriversEntity entity = findDriverForPasswordReset(dto.getAccount(), dto.getPhone());
        entity.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        driversDAO.save(entity);
    }

    public DriversDTO updateStatus(Long id, Boolean isActive) {
        DriversEntity entity = findEntity(id);
        entity.setIsActive(isActive);

        return toDTO(driversDAO.save(entity));
    }

    public DriversDTO updateProfilePhoto(Long id, MultipartFile photo) {
        DriversEntity entity = findEntity(id);
        entity.setProfilePhotoUrl(driverPhotoStorageService.store(photo));
        return toDTO(driversDAO.save(entity));
    }

    public void delete(Long id) {
        driversDAO.delete(findEntity(id));
    }

    private DriversEntity findEntity(Long id) {
        return driversDAO.findById(id).orElseThrow(() -> new EntityNotFoundException("找不到司機，ID：" + id));
    }

    private DriversEntity findDriverForPasswordReset(String account, String phone) {
        if (account == null || account.isBlank() || phone == null || phone.isBlank()) {
            throw new IllegalArgumentException("帳號或手機號碼不正確");
        }

        DriversEntity entity = driversDAO.findByAccount(account.trim())
                .orElseThrow(() -> new IllegalArgumentException("帳號或手機號碼不正確"));

        if (!phone.trim().equals(entity.getPhone()) || !Boolean.TRUE.equals(entity.getIsActive())) {
            throw new IllegalArgumentException("帳號或手機號碼不正確");
        }
        return entity;
    }

    private void validatePassword(String password, String blankMessage) {
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException(blankMessage);
        }
        if (password.length() < 8 || password.length() > 12) {
            throw new IllegalArgumentException("密碼長度必須介於 8 到 12 字元");
        }
    }

    /** 主管建立帳號時，身分證字號就是司機的初始密碼；資料庫只保存 BCrypt 雜湊。 */
    private void validateInitialNationalIdPassword(String password) {
        validatePassword(password, "新增司機時必須以身分證字號設定初始密碼");
        if (!password.matches("[A-Z][12]\\d{8}")) {
            throw new IllegalArgumentException("司機初始密碼必須是大寫的台灣身分證字號");
        }

        int letterCode = NATIONAL_ID_LETTER_CODES[password.charAt(0) - 'A'];
        int sum = letterCode / 10 + (letterCode % 10) * 9;
        for (int index = 1; index <= 8; index++) {
            sum += (password.charAt(index) - '0') * (9 - index);
        }
        sum += password.charAt(9) - '0';
        if (sum % 10 != 0) {
            throw new IllegalArgumentException("司機初始密碼的身分證檢查碼不正確");
        }
    }

    private void apply(DriversDTO dto, DriversEntity entity) {
        entity.setAccount(dto.getAccount());
        entity.setName(dto.getName());
        entity.setPhone(dto.getPhone());
        entity.setWorkStart(dto.getWorkStart());
        entity.setWorkEnd(dto.getWorkEnd());
        entity.setRestDuration(dto.getRestDuration());
        entity.setMaxOvertimeMinutes(dto.getMaxOvertimeMinutes());
        if (dto.getIsActive() != null) {
            entity.setIsActive(dto.getIsActive());
        }
    }

    private DriversDTO toDTO(DriversEntity entity) {
        DriversDTO dto = new DriversDTO();
        dto.setId(entity.getId());
        dto.setAccount(entity.getAccount());
        dto.setName(entity.getName());
        dto.setPhone(entity.getPhone());
        dto.setProfilePhotoUrl(entity.getProfilePhotoUrl());
        dto.setWorkStart(entity.getWorkStart());
        dto.setWorkEnd(entity.getWorkEnd());
        dto.setRestDuration(entity.getRestDuration());
        dto.setMaxOvertimeMinutes(entity.getMaxOvertimeMinutes());
        dto.setIsActive(entity.getIsActive());
        return dto;
    }
}
