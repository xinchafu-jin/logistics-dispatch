package com.example.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.file.*;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class PreTripPhotoStorageServiceTest {
    @TempDir Path directory;
    @Test void 圖片依內容識別且檔名不受使用者控制() throws Exception {
        var store = new PreTripPhotoStorageService(directory.toString()); var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB),"png",output);
        String name = store.store(new MockMultipartFile("file","../../unsafe.exe","image/jpeg",output.toByteArray()));
        assertTrue(name.endsWith(".png")); assertTrue(Files.exists(store.resolve(name)));
        assertThrows(IllegalArgumentException.class, () -> store.resolve("../outside.png"));
        store.discard(name); assertFalse(Files.exists(store.resolve(name)));
    }
    @Test void 空白超大或偽裝图片不得上传() {
        var store = new PreTripPhotoStorageService(directory.toString());
        assertThrows(IllegalArgumentException.class, () -> store.store(new MockMultipartFile("file",new byte[0])));
        assertThrows(IllegalArgumentException.class, () -> store.store(new MockMultipartFile("file","a.jpg","image/jpeg","not an image".getBytes())));
        assertThrows(IllegalArgumentException.class, () -> store.store(new MockMultipartFile("file",new byte[5*1024*1024+1])));
    }
}
