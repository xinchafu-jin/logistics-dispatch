package com.example.backend.dto.respones;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 一台車的保養狀況：現在離小保、大保、退役還有多遠，以及跑完這趟之後（預估）還剩多少。
 *
 * <p>「剩下」＝基準＋間隔－目前行車紀錄器里程；負數代表已經超過。
 * plannedKm 是這趟預計要跑的公里數（含回倉）；沒有要評估的趟次時是 null，預估欄位也都是 null。</p>
 *
 * <p>decision：</p>
 * <ul>
 *     <li>BLOCKED：車在保養／維修或已退役，或跑完這趟任一項會超過 → 擋發布、擋出車</li>
 *     <li>WARNING：跑完這趟任一項剩 0～warningKm 公里 → 只提醒（其他項缺資料也一樣是 WARNING）</li>
 *     <li>UNKNOWN：缺保養間隔、基準或里程，算得出來的項目也都還很遠 → 只提醒，不擋</li>
 *     <li>NORMAL：都還很遠</li>
 * </ul>
 */
public class VehicleMaintenanceSummaryResponse {

    public static final String NORMAL = "NORMAL";
    public static final String WARNING = "WARNING";
    public static final String BLOCKED = "BLOCKED";
    public static final String UNKNOWN = "UNKNOWN";

    private Integer currentOdometerKm;
    private Integer minorRemainingKm;
    private Integer majorRemainingKm;
    private Integer retirementRemainingKm;
    private Integer warningKm;
    private Double plannedKm;
    private Double projectedMinorKm;
    private Double projectedMajorKm;
    private Double projectedRetirementKm;
    private String decision;
    /** 給主管看的原因，例如「小保里程將超過 12.5 km」 */
    private List<String> reasons = List.of();
    private long minorCount;
    private long majorCount;
    private long repairCount;
    private LocalDateTime lastMinorAt;
    private LocalDateTime lastMajorAt;
    private LocalDateTime lastRepairAt;

    public Integer getCurrentOdometerKm() {
        return currentOdometerKm;
    }

    public void setCurrentOdometerKm(Integer currentOdometerKm) {
        this.currentOdometerKm = currentOdometerKm;
    }

    public Integer getMinorRemainingKm() {
        return minorRemainingKm;
    }

    public void setMinorRemainingKm(Integer minorRemainingKm) {
        this.minorRemainingKm = minorRemainingKm;
    }

    public Integer getMajorRemainingKm() {
        return majorRemainingKm;
    }

    public void setMajorRemainingKm(Integer majorRemainingKm) {
        this.majorRemainingKm = majorRemainingKm;
    }

    public Integer getRetirementRemainingKm() {
        return retirementRemainingKm;
    }

    public void setRetirementRemainingKm(Integer retirementRemainingKm) {
        this.retirementRemainingKm = retirementRemainingKm;
    }

    public Integer getWarningKm() {
        return warningKm;
    }

    public void setWarningKm(Integer warningKm) {
        this.warningKm = warningKm;
    }

    public Double getPlannedKm() {
        return plannedKm;
    }

    public void setPlannedKm(Double plannedKm) {
        this.plannedKm = plannedKm;
    }

    public Double getProjectedMinorKm() {
        return projectedMinorKm;
    }

    public void setProjectedMinorKm(Double projectedMinorKm) {
        this.projectedMinorKm = projectedMinorKm;
    }

    public Double getProjectedMajorKm() {
        return projectedMajorKm;
    }

    public void setProjectedMajorKm(Double projectedMajorKm) {
        this.projectedMajorKm = projectedMajorKm;
    }

    public Double getProjectedRetirementKm() {
        return projectedRetirementKm;
    }

    public void setProjectedRetirementKm(Double projectedRetirementKm) {
        this.projectedRetirementKm = projectedRetirementKm;
    }

    public String getDecision() {
        return decision;
    }

    public void setDecision(String decision) {
        this.decision = decision;
    }

    public List<String> getReasons() {
        return reasons;
    }

    public void setReasons(List<String> reasons) {
        this.reasons = reasons;
    }

    public long getMinorCount() {
        return minorCount;
    }

    public void setMinorCount(long minorCount) {
        this.minorCount = minorCount;
    }

    public long getMajorCount() {
        return majorCount;
    }

    public void setMajorCount(long majorCount) {
        this.majorCount = majorCount;
    }

    public long getRepairCount() {
        return repairCount;
    }

    public void setRepairCount(long repairCount) {
        this.repairCount = repairCount;
    }

    public LocalDateTime getLastMinorAt() {
        return lastMinorAt;
    }

    public void setLastMinorAt(LocalDateTime lastMinorAt) {
        this.lastMinorAt = lastMinorAt;
    }

    public LocalDateTime getLastMajorAt() {
        return lastMajorAt;
    }

    public void setLastMajorAt(LocalDateTime lastMajorAt) {
        this.lastMajorAt = lastMajorAt;
    }

    public LocalDateTime getLastRepairAt() {
        return lastRepairAt;
    }

    public void setLastRepairAt(LocalDateTime lastRepairAt) {
        this.lastRepairAt = lastRepairAt;
    }
}
