package com.example.backend.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class AdminStickyNoteRequestDTO {
    @Size(max = 120, message = "便利貼標題不能超過 120 字")
    private String title;

    @NotBlank(message = "便利貼內容不能為空")
    @Size(max = 2000, message = "便利貼內容不能超過 2000 字")
    private String content;

    @Size(max = 20, message = "便利貼顏色格式過長")
    private String color;

    @Min(value = 0, message = "便利貼排序不能小於 0")
    private Integer sortOrder;

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public Integer getSortOrder() { return sortOrder; }
    public void setSortOrder(Integer sortOrder) { this.sortOrder = sortOrder; }
}
