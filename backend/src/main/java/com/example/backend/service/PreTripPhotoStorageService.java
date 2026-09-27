package com.example.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/** 檢查照片不放在公開 uploads handler，讀取必須通過任務所有權驗證。 */
@Service
public class PreTripPhotoStorageService {
    private final Path directory;
    public PreTripPhotoStorageService(@Value("${app.storage.pre-trip-dir:uploads/pre-trip}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }
    public String store(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("酒測、車輛檢點與行車紀錄器照片皆必填");
        if (file.getSize() > 5L * 1024 * 1024) throw new IllegalArgumentException("每張檢查照片不可超過 5 MB");
        try {
            byte[] bytes = file.getBytes();
            String extension = extension(bytes);
            if (extension == null) throw new IllegalArgumentException("檢查照片只支援 JPG、PNG、WebP");
            Files.createDirectories(directory);
            String name = UUID.randomUUID() + extension;
            Files.write(resolve(name), bytes, StandardOpenOption.CREATE_NEW);
            return name;
        } catch (IOException e) { throw new IllegalStateException("檢查照片儲存失敗", e); }
    }
    public Path resolve(String name) {
        Path path = directory.resolve(name).normalize();
        if (!path.startsWith(directory) || !path.getParent().equals(directory)) throw new IllegalArgumentException("照片路徑不合法");
        return path;
    }
    public void discard(String name) {
        try { Files.deleteIfExists(resolve(name)); }
        catch (IOException e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("無法清理未提交檢查照片 {}", name, e); }
    }
    private String extension(byte[] b) {
        if (b.length >= 3 && (b[0] & 255) == 255 && (b[1] & 255) == 216 && (b[2] & 255) == 255) return ".jpg";
        if (b.length >= 8 && (b[0] & 255) == 137 && b[1] == 80 && b[2] == 78 && b[3] == 71
                && b[4] == 13 && b[5] == 10 && b[6] == 26 && b[7] == 10) return ".png";
        if (b.length >= 12 && b[0] == 82 && b[1] == 73 && b[2] == 70 && b[3] == 70
                && b[8] == 87 && b[9] == 69 && b[10] == 66 && b[11] == 80) return ".webp";
        return null;
    }
}
