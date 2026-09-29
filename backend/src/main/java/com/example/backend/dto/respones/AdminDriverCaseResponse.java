package com.example.backend.dto.respones;

/**
 * 異常中心看到的司機回報案件：司機端的欄位，再加上誰回報、哪條路線哪台車、誰接收、誰結案。
 *
 * <p>只給後台：/api/exceptions/driver-cases 的回應，和管理員頻道的推播。
 * Jackson 依實際類別輸出欄位，放進給司機的回應或推播，這些欄位就會一起送出去。</p>
 */
public class AdminDriverCaseResponse extends DriverCaseResponse {

    /** 舊版 API 建的回報沒有記司機，會是 null */
    private Long driverId;
    private String driverName;
    private Long routeId;
    /** 路線排的車；車輛故障時主管要知道是哪一台 */
    private String vehiclePlateNumber;
    private Long acceptedAdminId;
    private String acceptedAdminName;
    /** 結案的管理員名稱（跟一般異常的 handledBy 一樣存名字） */
    private String handledBy;

    public Long getDriverId() {
        return driverId;
    }

    public void setDriverId(Long driverId) {
        this.driverId = driverId;
    }

    public String getDriverName() {
        return driverName;
    }

    public void setDriverName(String driverName) {
        this.driverName = driverName;
    }

    public Long getRouteId() {
        return routeId;
    }

    public void setRouteId(Long routeId) {
        this.routeId = routeId;
    }

    public String getVehiclePlateNumber() {
        return vehiclePlateNumber;
    }

    public void setVehiclePlateNumber(String vehiclePlateNumber) {
        this.vehiclePlateNumber = vehiclePlateNumber;
    }

    public Long getAcceptedAdminId() {
        return acceptedAdminId;
    }

    public void setAcceptedAdminId(Long acceptedAdminId) {
        this.acceptedAdminId = acceptedAdminId;
    }

    public String getAcceptedAdminName() {
        return acceptedAdminName;
    }

    public void setAcceptedAdminName(String acceptedAdminName) {
        this.acceptedAdminName = acceptedAdminName;
    }

    public String getHandledBy() {
        return handledBy;
    }

    public void setHandledBy(String handledBy) {
        this.handledBy = handledBy;
    }
}
