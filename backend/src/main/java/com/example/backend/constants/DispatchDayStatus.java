package com.example.backend.constants;

/**
 * 看板日期列上，某一天（全部倉庫合起來）的狀態。
 * 不存資料庫，每次都由當天的路線與訂單狀態推算，理由同拿掉 SCHEDULED：多存一份就要擔心不同步。
 */
public enum DispatchDayStatus {
    EMPTY,       // 沒有任何有效訂單（取消的不算）
    UNPLANNED,   // 有單，還沒有路線
    DRAFT,       // 有路線，還有草稿沒發布
    PUBLISHED,   // 全部路線都已發布，還沒有單開始動
    IN_PROGRESS, // 有單已點交、配送中或已結束，但還沒全部結束
    CLOSED,      // 全部訂單都已結束（完成、無人簽收、點交不符）
    UNRESOLVED   // 日期已過，還有單沒結束（含沒人確認的補送單）
}
