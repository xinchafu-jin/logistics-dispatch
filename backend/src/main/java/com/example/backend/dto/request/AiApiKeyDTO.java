package com.example.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AiApiKeyDTO {
    @NotBlank(message = "API Key 不可空白")
    @Size(max = 200, message = "API Key 長度不可超過 200 字元")
    private String apiKey;

    public AiApiKeyDTO() {
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }
}
