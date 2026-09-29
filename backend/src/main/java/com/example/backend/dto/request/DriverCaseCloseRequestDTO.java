package com.example.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** 主管結案司機回報時送的：處理結果，加上要改期補送的單。 */
public class DriverCaseCloseRequestDTO {

    @NotBlank(message = "處理結果不能為空")
    @Size(max = 1000, message = "處理結果不能超過 1000 字")
    private String resolution;

    /** 案件路線上要改期補送的單；不帶或空的代表都不動（當天的單讓司機繼續送） */
    private List<Long> redeliverOrderIds;

    public String getResolution() {
        return resolution;
    }

    public void setResolution(String resolution) {
        this.resolution = resolution;
    }

    public List<Long> getRedeliverOrderIds() {
        return redeliverOrderIds;
    }

    public void setRedeliverOrderIds(List<Long> redeliverOrderIds) {
        this.redeliverOrderIds = redeliverOrderIds;
    }
}
