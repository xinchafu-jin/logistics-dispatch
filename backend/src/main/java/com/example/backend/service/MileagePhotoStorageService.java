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

/** 儲存出車與收車時的里程表照片，與交貨照片分開管理。 */
@Service
public class MileagePhotoStorageService {

    public static final String PUBLIC_URL_PREFIX = "/uploads/mileage-photos/";
    private static final long MAX_FILE_SIZE = 5L * 1024L * 1024L;
    private final Path storageDirectory;

    public MileagePhotoStorageService(
            @Value("${app.storage.mileage-photos-dir:uploads/mileage-photos}") String storageDirectory
    ) {
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
    }

    public String store(MultipartFile photo) {
        if (photo == null || photo.isEmpty()) {
            throw new IllegalArgumentException("請選擇里程表照片");
        }
        if (photo.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("里程表照片不可超過 5 MB");
        }
        try (InputStream source = photo.getInputStream()) {
            byte[] header = source.readNBytes(12);
            String extension = imageExtension(header);
            if (extension == null) {
                throw new IllegalArgumentException("里程表照片只支援 JPG、PNG 或 WebP");
            }
            Files.createDirectories(storageDirectory);
            Path target = storageDirectory.resolve(UUID.randomUUID() + extension).normalize();
            if (!target.startsWith(storageDirectory)) {
                throw new IllegalArgumentException("里程表照片檔名不合法");
            }
            try (InputStream completed = new SequenceInputStream(new ByteArrayInputStream(header), source)) {
                Files.copy(completed, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return PUBLIC_URL_PREFIX + target.getFileName();
        } catch (IOException exception) {
            throw new IllegalStateException("里程表照片儲存失敗", exception);
        }
    }

    private static String imageExtension(byte[] header) {
        if (header.length >= 3 && unsigned(header[0]) == 0xff
                && unsigned(header[1]) == 0xd8 && unsigned(header[2]) == 0xff) {
            return ".jpg";
        }
        if (header.length >= 8 && unsigned(header[0]) == 0x89 && header[1] == 'P'
                && header[2] == 'N' && header[3] == 'G' && unsigned(header[4]) == 0x0d
                && unsigned(header[5]) == 0x0a && unsigned(header[6]) == 0x1a && unsigned(header[7]) == 0x0a) {
            return ".png";
        }
        if (header.length >= 12 && header[0] == 'R' && header[1] == 'I'
                && header[2] == 'F' && header[3] == 'F' && header[8] == 'W'
                && header[9] == 'E' && header[10] == 'B' && header[11] == 'P') {
            return ".webp";
        }
        return null;
    }

    private static int unsigned(byte value) {
        return value & 0xff;
    }
}
