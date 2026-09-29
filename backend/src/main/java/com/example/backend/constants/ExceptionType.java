package com.example.backend.constants;

public enum ExceptionType {
    /** 無人簽收 */
    NO_SIGNATURE,
    /** 實到數量少於應到數量 */
    SHORTAGE,
    /** 實到貨物中有損毀 */
    DAMAGE,
    /** 同一次交貨同時發生短少與損毀 */
    SHORTAGE_AND_DAMAGE,
    /** 司機例外回報 */
    DRIVER_REPORT,
    /** 電話處理後補登 */
    PHONE_HANDLED,
    /** 倉庫點交時實點箱數與應到箱數不符 */
    LOADING_MISMATCH,
    /** 配送日已過，訂單仍未確認或尚未開始點交配送 */
    UNSETTLED_ORDER
}
