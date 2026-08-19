package com.example.backend.constants;

public final class ValidMsg {

    private ValidMsg() {
    }

    // Driver
    public static final String DRIVER_ACCOUNT_REQUIRED = "司機帳號不能為空";
    public static final String DRIVER_ACCOUNT_MAX_LENGTH = "帳號長度不能超過 50 字元";
    public static final String DRIVER_NAME_REQUIRED = "司機姓名不能為空";
    public static final String DRIVER_NAME_MAX_LENGTH = "姓名長度不能超過 30 字元";
    public static final String DRIVER_PHONE_MAX_LENGTH = "電話長度不能超過 10 字元";
    public static final String DRIVER_WORK_START_REQUIRED = "上班時間不能為空";
    public static final String DRIVER_WORK_END_REQUIRED = "下班時間不能為空";
    public static final String DRIVER_REST_DURATION_REQUIRED = "休息時長不能為空";
    public static final String DRIVER_REST_DURATION_MIN = "休息時長不能小於 0";
    public static final String DRIVER_OVERTIME_MIN = "加班上限不能小於 0";
    public static final String DRIVER_ACTIVE_REQUIRED = "請選擇司機是否在職";

    // Order
    public static final String ORDER_NUMBER_REQUIRED = "訂單編號不能為空";
    public static final String ORDER_NUMBER_MAX_LENGTH = "訂單編號不能超過 30 字元";
    public static final String ORDER_STORE_ID_REQUIRED = "門市 ID 不能為空";
    public static final String ORDER_VENDOR_MAX_LENGTH = "來源商家長度不能超過 100 字元";
    public static final String ORDER_ITEM_DESCRIPTION_MAX_LENGTH = "商品描述不能超過 255 字元";
    public static final String ORDER_BOX_COUNT_REQUIRED = "箱數不能為空";
    public static final String ORDER_BOX_COUNT_MIN = "箱數至少為 1";
    public static final String ORDER_BOX_VOLUME_REQUIRED = "體積不能為空";
    public static final String ORDER_BOX_VOLUME_MIN = "體積不能小於 0";
    public static final String ORDER_DELIVERY_DATE_REQUIRED = "配送日期不能為空";
    public static final String ORDER_STATUS_REQUIRED = "訂單狀態不能為空";

    // Store
    public static final String STORE_CODE_REQUIRED = "門市代碼不能為空";
    public static final String STORE_CODE_MAX_LENGTH = "門市代碼不能超過 20 字元";
    public static final String STORE_NAME_REQUIRED = "門市名稱不能為空";
    public static final String STORE_NAME_MAX_LENGTH = "門市名稱不能超過 100 字元";
    public static final String STORE_ADDRESS_MAX_LENGTH = "地址長度不能超過 255 字元";
    public static final String STORE_LAT_REQUIRED = "緯度不能為空";
    public static final String STORE_LNG_REQUIRED = "經度不能為空";
    public static final String STORE_CONTACT_NAME_MAX_LENGTH = "聯絡人名稱不能超過 50 字元";
    public static final String STORE_PHONE_MAX_LENGTH = "電話長度不能超過 30 字元";
    public static final String STORE_RECEIVING_START_REQUIRED = "收貨開始時間不能為空";
    public static final String STORE_RECEIVING_END_REQUIRED = "收貨結束時間不能為空";
    public static final String STORE_STATUS_REQUIRED = "門市狀態不能為空";

    // Vehicle
    public static final String VEHICLE_PLATE_REQUIRED = "車牌號碼不能為空";
    public static final String VEHICLE_PLATE_MAX_LENGTH = "車牌號碼不能超過 20 字元";
    public static final String VEHICLE_TYPE_MAX_LENGTH = "車輛類型不能超過 50 字元";
    public static final String VEHICLE_CAPACITY_REQUIRED = "可用容量不能為空";
    public static final String VEHICLE_CAPACITY_MIN = "容量不能小於 0";
    public static final String VEHICLE_FUEL_CONSUMPTION_MIN = "平均油耗不能小於 0";
    public static final String VEHICLE_STATUS_REQUIRED = "車輛狀態不能為空";

    // Warehouse
    public static final String WAREHOUSE_CODE_REQUIRED = "倉庫代碼不能為空";
    public static final String WAREHOUSE_CODE_MAX_LENGTH = "倉庫代碼不能超過 20 字元";
    public static final String WAREHOUSE_NAME_REQUIRED = "倉庫名稱不能為空";
    public static final String WAREHOUSE_NAME_MAX_LENGTH = "倉庫名稱不能超過 100 字元";
    public static final String WAREHOUSE_ADDRESS_MAX_LENGTH = "地址長度不能超過 255 字元";
    public static final String WAREHOUSE_LAT_REQUIRED = "倉庫緯度不能為空";
    public static final String WAREHOUSE_LNG_REQUIRED = "倉庫經度不能為空";
    public static final String WAREHOUSE_PHONE_MAX_LENGTH = "電話長度不能超過 30 字元";}