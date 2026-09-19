package com.example.backend.dto.respones;

import java.time.LocalDateTime;

/** AI API Key 的設定狀態；永遠不含完整 Key。 */
public class AiApiKeyStatusResponse {
    private final boolean configured;
    private final String maskedKey;
    private final LocalDateTime updatedAt;

    public AiApiKeyStatusResponse(boolean configured, String maskedKey, LocalDateTime updatedAt) {
        this.configured = configured;
        this.maskedKey = maskedKey;
        this.updatedAt = updatedAt;
    }

    public boolean isConfigured() {
        return configured;
    }

    public String getMaskedKey() {
        return maskedKey;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
