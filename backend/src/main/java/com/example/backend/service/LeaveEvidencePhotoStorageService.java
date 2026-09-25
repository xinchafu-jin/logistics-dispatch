package com.example.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

@Service
public class LeaveEvidencePhotoStorageService {
    public static final String PUBLIC_URL_PREFIX = "/uploads/leave-evidence/";
    private static final long MAX_FILE_SIZE = 5L * 1024L * 1024L;

    private final Path storageDirectory;

    public LeaveEvidencePhotoStorageService(
            @Value("${app.storage.leave-evidence-dir:uploads/leave-evidence}") String storageDirectory
    ) {
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
    }

    public String store(MultipartFile photo) {
        if (photo == null || photo.isEmpty()) {
            throw new IllegalArgumentException("請選擇請假佐證照片");
        }
        if (photo.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("請假佐證照片不可超過 5 MB");
        }
        try (InputStream source = photo.getInputStream()) {
            byte[] header = source.readNBytes(12);
            String extension = detectExtension(header);
            if (extension == null) {
                throw new IllegalArgumentException("請假佐證照片只支援 JPG、PNG 或 WebP");
            }
            Files.createDirectories(storageDirectory);
            Path target = storageDirectory.resolve(UUID.randomUUID() + extension).normalize();
            if (!target.startsWith(storageDirectory)) {
                throw new IllegalArgumentException("請假佐證照片檔名不合法");
            }
            try (InputStream completeSource = new SequenceInputStream(
                    new ByteArrayInputStream(header), source)) {
                Files.copy(completeSource, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return PUBLIC_URL_PREFIX + target.getFileName();
        } catch (IOException exception) {
            throw new IllegalStateException("請假佐證照片儲存失敗", exception);
        }
    }

    private String detectExtension(byte[] header) {
        if (header.length >= 3 && unsigned(header[0]) == 0xFF
                && unsigned(header[1]) == 0xD8 && unsigned(header[2]) == 0xFF) {
            return ".jpg";
        }
        if (header.length >= 8 && unsigned(header[0]) == 0x89
                && header[1] == 'P' && header[2] == 'N' && header[3] == 'G'
                && unsigned(header[4]) == 0x0D && unsigned(header[5]) == 0x0A
                && unsigned(header[6]) == 0x1A && unsigned(header[7]) == 0x0A) {
            return ".png";
        }
        if (header.length >= 12 && header[0] == 'R' && header[1] == 'I'
                && header[2] == 'F' && header[3] == 'F' && header[8] == 'W'
                && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return ".webp";
        }
        return null;
    }

    private int unsigned(byte value) {
        return value & 0xFF;
    }
}
