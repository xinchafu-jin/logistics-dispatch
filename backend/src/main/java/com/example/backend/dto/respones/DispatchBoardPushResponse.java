package com.example.backend.dto.respones;

import java.time.LocalDate;

/**
 * 看板推播：只說哪一天有變動，前端收到後自己重打 /days、/board 拿最新資料。
 * 不推整份看板：資料量大，而且畫面只有一個資料來源（API），漏收一則也只是晚一點更新，不會顯示錯的。
 */
public class DispatchBoardPushResponse {

    private LocalDate date;

    public DispatchBoardPushResponse() {
    }

    public DispatchBoardPushResponse(LocalDate date) {
        this.date = date;
    }

    public LocalDate getDate() {
        return date;
    }

    public void setDate(LocalDate date) {
        this.date = date;
    }
}
