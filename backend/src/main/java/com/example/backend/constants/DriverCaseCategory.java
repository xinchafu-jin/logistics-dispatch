package com.example.backend.constants;

/**
 * 司機回報案件的分類，存在 exception_cases.category（VARCHAR，存 enum 名稱）。
 *
 * <p>中文名稱、圖示、快選情境只放在司機端前端（driver-dashboard.ts 的 CASE_CATEGORIES）。
 * 這裡加值不用改資料庫，但前端要跟著加，不然前端會把不認識的分類顯示成「其他」。</p>
 */
public enum DriverCaseCategory {
    /** 車輛問題：無法發動、爆胎、警示燈亮 */
    VEHICLE,
    /** 交通事故 */
    ACCIDENT,
    /** 路況延誤：塞車、封路、淹水 */
    ROAD,
    /** 門市狀況：找不到門市、無法停車卸貨 */
    STORE,
    /** 貨物問題：外箱破損、裝錯貨 */
    GOODS,
    /** 身體不適或人身安全 */
    PERSONAL,
    /** App 或系統問題 */
    SYSTEM,
    /** 其他 */
    OTHER
}
