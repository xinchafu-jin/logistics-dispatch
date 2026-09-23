package com.example.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import static com.example.backend.constants.ValidMsg.DRIVER_MESSAGE_CONTENT_MAX_LENGTH;
import static com.example.backend.constants.ValidMsg.DRIVER_MESSAGE_CONTENT_REQUIRED;

/**
 * 司機發訊息、管理員回覆共用。只收內容：
 * 對話屬於哪位司機、誰發的、什麼時間，都由後端決定，不接受前端指定。
 */
public class DriverMessageRequestDTO {

    // 上限跟 driver_messages.content 的 varchar(1000) 一致
    @NotBlank(message = DRIVER_MESSAGE_CONTENT_REQUIRED)
    @Size(max = 1000, message = DRIVER_MESSAGE_CONTENT_MAX_LENGTH)
    private String content;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
