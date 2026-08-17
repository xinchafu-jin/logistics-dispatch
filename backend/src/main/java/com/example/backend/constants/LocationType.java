package com.example.backend.constants;

/**
 * 地點類型。距離矩陣的起訖點可能是倉庫或門市，
 * 兩者 id 各自獨立，需搭配類型才能唯一識別。
 */
public enum LocationType {
    WAREHOUSE,
    STORE
}
