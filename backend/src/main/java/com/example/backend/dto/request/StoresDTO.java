package com.example.backend.dto.request;

import com.example.backend.constants.StoreStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalTime;
import static com.example.backend.constants.ValidMsg.*;

public class StoresDTO {

    private Long id;

    @NotBlank(message = STORE_CODE_REQUIRED)
    @Size(max = 20, message = STORE_CODE_MAX_LENGTH)
    private String storeCode;

    @NotBlank(message = STORE_NAME_REQUIRED)
    @Size(max = 100, message = STORE_NAME_MAX_LENGTH)
    private String name;

    @Size(max = 255, message = STORE_ADDRESS_MAX_LENGTH)
    private String address;

    @NotNull(message = STORE_LAT_REQUIRED)
    private Double lat;

    @NotNull(message = STORE_LNG_REQUIRED)
    private Double lng;

    @Size(max = 50, message = STORE_CONTACT_NAME_MAX_LENGTH)
    private String contactName;

    @Size(max = 30, message = STORE_PHONE_MAX_LENGTH)
    private String phone;

    @NotNull(message = STORE_RECEIVING_START_REQUIRED)
    private LocalTime receivingStart;

    @NotNull(message = STORE_RECEIVING_END_REQUIRED)
    private LocalTime receivingEnd;

    private String notes;

    @NotNull(message = STORE_STATUS_REQUIRED)
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