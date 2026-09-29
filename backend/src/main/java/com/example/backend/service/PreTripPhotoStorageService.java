package com.example.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 儲存出車前安全檢查的照片（酒測器讀數、故障照片）。
 *
 * <p>跟行車紀錄器（出車里程）、交貨照片不同，這裡的照片不註冊到 DriverPhotoResourceConfig 的公開路徑：
 * 酒測結果屬於個人資料，只存檔名，讀取要經過 PreTripInspectionService.photo 確認是本人。</p>
 */
@Service
public class PreTripPhotoStorageService {

    private static final Logger log = LoggerFactory.getLogger(PreTripPhotoStorageService.class);
    private static final long MAX_FILE_SIZE = 5L * 1024L * 1024L;
    private final Path storageDirectory;

    public PreTripPhotoStorageService(
            @Value("${app.storage.pre-trip-photos-dir:uploads/pre-trip-photos}") String storageDirectory
    ) {
        this.storageDirectory = Path.of(storageDirectory).toAbsolutePath().normalize();
    }

    /**
     * 存檔並回傳檔名。檔名一律由系統產生（UUID），副檔名看檔案開頭的位元組決定，
     * 不信任使用者給的檔名或 Content-Type，改副檔名偽裝的檔案存不進來。
     */
    public String store(MultipartFile photo, String label) {
        if (photo == null || photo.isEmpty()) {
            throw new IllegalArgumentException("請拍" + label + "照片");
        }
        if (photo.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException(label + "照片不可超過 5 MB");
        }
        try (InputStream source = photo.getInputStream()) {
            byte[] header = source.readNBytes(12);
            String extension = imageExtension(header);
            if (extension == null) {
                throw new IllegalArgumentException(label + "照片只支援 JPG、PNG 或 WebP");
            }
            Files.createDirectories(storageDirectory);
            String fileName = UUID.randomUUID() + extension;
            Path target = resolve(fileName);
            // 前 12 個位元組已經讀掉拿去判斷格式，接回去才是完整的檔案
            try (InputStream completed = new SequenceInputStream(new ByteArrayInputStream(header), source)) {
                Files.copy(completed, target);
            }
            return fileName;
        } catch (IOException exception) {
            throw new IllegalStateException(label + "照片儲存失敗", exception);
        }
    }

    /** 檔名換成實際路徑；只接受這個資料夾底下的檔案，擋掉 ../ 之類跳出資料夾的檔名 */
    public Path resolve(String fileName) {
        Path path = storageDirectory.resolve(fileName).normalize();
        if (!storageDirectory.equals(path.getParent())) {
            throw new IllegalArgumentException("照片檔名不合法");
        }
        return path;
    }

    /** 交易沒有成功時刪掉已經存下的照片；刪不掉只記 log，不讓原本的錯誤被蓋掉 */
    public void discard(String fileName) {
        try {
            Files.deleteIfExists(resolve(fileName));
        } catch (IOException | IllegalArgumentException exception) {
            log.warn("無法刪除沒有用到的安全檢查照片 {}", fileName, exception);
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
