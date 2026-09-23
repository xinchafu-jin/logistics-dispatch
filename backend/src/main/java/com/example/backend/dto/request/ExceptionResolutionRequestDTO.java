package com.example.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 主管結案一般配送異常時填寫的處理結果。 */
public class ExceptionResolutionRequestDTO {

    @NotBlank(message = "處理結果不能為空")
    @Size(max = 1000, message = "處理結果不能超過 1000 字")
    private String resolution;

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }
}
