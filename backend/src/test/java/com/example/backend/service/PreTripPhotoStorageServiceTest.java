package com.example.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 安全檢查照片：格式看檔案內容、檔名由系統產生、讀取不能跳出資料夾。用暫存資料夾，不碰真的 uploads。 */
class PreTripPhotoStorageServiceTest {

    @TempDir
    Path directory;

    @Test
    void 副檔名看檔案內容決定_不看使用者給的檔名() throws IOException {
        PreTripPhotoStorageService storage = new PreTripPhotoStorageService(directory.toString());

        // 內容是 PNG，檔名卻取成 .exe、還想跳出資料夾
        String fileName = storage.store(new MockMultipartFile("file", "../../unsafe.exe", "image/jpeg", png()), "酒測器讀數");

        assertTrue(fileName.endsWith(".png"));
        assertTrue(Files.exists(storage.resolve(fileName)));
        assertEquals(directory.toAbsolutePath().normalize(), storage.resolve(fileName).getParent());
    }

    @Test
    void 讀取不能跳出照片資料夾() {
        PreTripPhotoStorageService storage = new PreTripPhotoStorageService(directory.toString());

        assertThrows(IllegalArgumentException.class, () -> storage.resolve("../outside.png"));
        assertThrows(IllegalArgumentException.class, () -> storage.resolve("sub/inside.png"));
    }

    @Test
    void 空檔案_偽裝成圖片的文字檔_超過5MB都存不進來() {
        PreTripPhotoStorageService storage = new PreTripPhotoStorageService(directory.toString());

        IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> storage.store(new MockMultipartFile("file", new byte[0]), "酒測器讀數"));
        assertEquals("請拍酒測器讀數照片", empty.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> storage.store(new MockMultipartFile("file", "a.jpg", "image/jpeg", "not an image".getBytes()), "故障"));
        assertThrows(IllegalArgumentException.class,
                () -> storage.store(new MockMultipartFile("file", new byte[5 * 1024 * 1024 + 1]), "故障"));
    }

    @Test
    void 刪除沒用到的照片() throws IOException {
        PreTripPhotoStorageService storage = new PreTripPhotoStorageService(directory.toString());
        String fileName = storage.store(new MockMultipartFile("file", "a.png", "image/png", png()), "故障");

        storage.discard(fileName);

        assertFalse(Files.exists(storage.resolve(fileName)));
    }

    /** 1×1 的真 PNG，檔頭才會是 PNG 的簽章 */
    private byte[] png() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}
