package com.example.backend.dto.respones;

import com.example.backend.constants.DispatchDayStatus;

import java.time.LocalDate;

/**
 * 看板日期列的一格：某一天全部倉庫合起來的狀態與數量。
 * 詳細內容點進去再用 /api/dispatch/board 查，這裡只給一眼看得懂的摘要。
 */
public class DispatchDayResponse {

    private LocalDate date;

    private DispatchDayStatus status;
    /** 當天是否仍有已發布路線；不可用訂單進度推斷。 */
    private boolean published;

    /** 有效訂單數，不含取消的單 */
    private int orderCount;

    /** 待確認（PENDING_CONFIRM） */
    private int pendingConfirmCount;

    /** 已確認、還沒排進路線 */
    private int unassignedCount;

    /** 已結束：完成、無人簽收、點交不符 */
    private int finishedCount;

    public DispatchDayResponse() {
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }

    public DispatchDayStatus getStatus() {
        return status;
    }

    public void setStatus(DispatchDayStatus status) {
        this.status = status;
    }

    public boolean isPublished() {
        return published;
    }

    public void setPublished(boolean published) {
        this.published = published;
    }

    public int getOrderCount() {
        return orderCount;
    }

    public void setOrderCount(int orderCount) {
        this.orderCount = orderCount;
    }

    public int getPendingConfirmCount() {
        return pendingConfirmCount;
    }

    public void setPendingConfirmCount(int pendingConfirmCount) {
        this.pendingConfirmCount = pendingConfirmCount;
    }

    public int getUnassignedCount() {
        return unassignedCount;
    }

    public void setUnassignedCount(int unassignedCount) {
        this.unassignedCount = unassignedCount;
    }

    public int getFinishedCount() {
        return finishedCount;
    }

    public void setFinishedCount(int finishedCount) {
        this.finishedCount = finishedCount;
    }
}
