package com.example.backend.dto;

import com.example.backend.constans.StoreStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;

public class StoresDTO {

    private Long id;

    @NotBlank(message = "門市代碼不能為空")
    @Size(max = 20, message = "門市代碼不能超過 20 字元")
    private String storeCode;

    @NotBlank(message = "門市名稱不能為空")
    @Size(max = 100, message = "門市名稱不能超過 100 字元")
    private String name;

    @Size(max = 255, message = "地址長度不能超過 255 字元")
    private String address;

    @NotNull(message = "緯度(lat)不能為空")
    private Double lat;

    @NotNull(message = "經度(lng)不能為空")
    private Double lng;

    @Size(max = 50, message = "聯絡人名稱不能超過 50 字元")
    private String contactName;

    @Size(max = 30, message = "電話長度不能超過 30 字元")
    private String phone;

    @NotNull(message = "收貨開始時間不能為空")
    private LocalTime receivingStart;

    @NotNull(message = "收貨結束時間不能為空")
    private LocalTime receivingEnd;

    private String notes;

    @NotNull(message = "門市狀態不能為空")
    private StoreStatus status;

    // ===== Getter & Setter =====
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getStoreCode() { return storeCode; }
    public void setStoreCode(String storeCode) { this.storeCode = storeCode; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }

    public Double getLat() { return lat; }
    public void setLat(Double lat) { this.lat = lat; }

    public Double getLng() { return lng; }
    public void setLng(Double lng) { this.lng = lng; }

    public String getContactName() { return contactName; }
    public void setContactName(String contactName) { this.contactName = contactName; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public LocalTime getReceivingStart() { return receivingStart; }
    public void setReceivingStart(LocalTime receivingStart) { this.receivingStart = receivingStart; }

    public LocalTime getReceivingEnd() { return receivingEnd; }
    public void setReceivingEnd(LocalTime receivingEnd) { this.receivingEnd = receivingEnd; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public StoreStatus getStatus() { return status; }
    public void setStatus(StoreStatus status) { this.status = status; }
}