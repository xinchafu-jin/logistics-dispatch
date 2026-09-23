package com.example.backend.controller;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 驗證 /uploads/driver-photos/** 的讀取：請求經過真的 Security 過濾器與 DriverPhotoResourceConfig。
 *
 * <p>照片目錄指到暫存資料夾，不會碰到專案底下的 uploads/。</p>
 */
@SpringBootTest(properties = {
        "app.crypto.password=test-only-password-test-only-password",
        "app.crypto.salt=0123456789abcdef"
})
class UploadedPhotoAccessTest {

    // 1x1 透明 PNG
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private static final Path PHOTO_DIR = createTempDir();

    @DynamicPropertySource
    static void photoDirectory(DynamicPropertyRegistry registry) {
        registry.add("app.storage.driver-photos-dir", PHOTO_DIR::toString);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter springSecurityFilterChain;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(springSecurityFilterChain)
                .build();
    }

    @Test
    void 照片存在時_不帶token也讀得到() throws Exception {
        Files.write(PHOTO_DIR.resolve("exists.png"), PNG);

        mockMvc.perform(get("/uploads/driver-photos/exists.png"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG));
    }

    @Test
    void 照片不存在時_回404而不是500() throws Exception {
        mockMvc.perform(get("/uploads/driver-photos/does-not-exist.png"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("找不到檔案"));
    }

    private static Path createTempDir() {
        try {
            Path dir = Files.createTempDirectory("driver-photos-test");
            dir.toFile().deleteOnExit();
            return dir;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
