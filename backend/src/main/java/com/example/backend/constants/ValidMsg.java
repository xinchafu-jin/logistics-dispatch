package com.example.backend.constants;

public final class ValidMsg {

    private ValidMsg() {
    }

    // Driver
    public static final String DRIVER_ACCOUNT_REQUIRED = "司機帳號不能為空";
    public static final String DRIVER_ACCOUNT_MAX_LENGTH = "帳號長度不能超過 50 字元";
    public static final String DRIVER_NAME_REQUIRED = "司機姓名不能為空白";
    public static final String DRIVER_NAME_MAX_LENGTH = "姓名長度不能超過 30 字元";
    public static final String DRIVER_PHONE_MAX_LENGTH = "電話長度不能超過 10 字元";
    public static final String DRIVER_WORK_START_REQUIRED = "上班時間不能為空";
    public static final String DRIVER_WORK_END_REQUIRED = "下班時間不能為空";
    public static final String DRIVER_REST_DURATION_REQUIRED = "休息時長不能為空";
    public static final String DRIVER_REST_DURATION_MIN = "休息時長不能小於 0";
    public static final String DRIVER_OVERTIME_MIN = "加班上限不能小於 0";
    public static final String DRIVER_ACTIVE_REQUIRED = "請選擇司機是否在職";

    // Driver account application
    public static final String DRIVER_APPLICATION_ACCOUNT_REQUIRED = "登入帳號不能為空";
    public static final String DRIVER_APPLICATION_ACCOUNT_MAX_LENGTH = "登入帳號不能超過 50 字元";
    public static final String DRIVER_APPLICATION_NAME_REQUIRED = "司機姓名不能為空";
    public static final String DRIVER_APPLICATION_NAME_MAX_LENGTH = "司機姓名不能超過 30 字元";
    public static final String DRIVER_APPLICATION_PHONE_REQUIRED = "手機號碼不能為空";
    public static final String DRIVER_APPLICATION_PHONE_FORMAT = "手機號碼必須是 09 開頭的 10 位數字";
    public static final String DRIVER_APPLICATION_NATIONAL_ID_REQUIRED = "身分證字號不能為空";
    public static final String DRIVER_APPLICATION_NATIONAL_ID_FORMAT = "身分證字號格式不正確";

    // Reassign（拖曳改派）
    public static final String REASSIGN_DATE_REQUIRED = "配送日期不能為空";
    public static final String REASSIGN_WAREHOUSE_ID_REQUIRED = "倉庫不能為空";
    public static final String REASSIGN_ROUTES_REQUIRED = "路線清單不能為空";
    public static final String REASSIGN_VEHICLE_ID_REQUIRED = "車輛不能為空";
    public static final String REASSIGN_ORDER_IDS_REQUIRED = "訂單清單不能為空";

    // Order
    public static final String ORDER_NUMBER_REQUIRED = "訂單編號不能為空";
    public static final String ORDER_NUMBER_MAX_LENGTH = "訂單編號不能超過 30 字元";
    public static final String ORDER_STORE_ID_REQUIRED = "門市 ID 不能為空";
    public static final String ORDER_STORE_ID_POSITIVE = "門市 ID 必須大於 0";
    public static final String ORDER_VENDOR_MAX_LENGTH = "來源商家長度不能超過 100 字元";
    public static final String ORDER_ITEM_DESCRIPTION_MAX_LENGTH = "商品描述不能超過 255 字元";
    public static final String ORDER_BOX_COUNT_REQUIRED = "箱數不能為空";
    public static final String ORDER_BOX_COUNT_MIN = "箱數至少為 1";
    public static final String ORDER_NOTES_MAX_LENGTH = "備註長度不能超過 500 字元";
    public static final String ORDER_DELIVERY_DATE_REQUIRED = "配送日期不能為空";
    public static final String ORDER_STATUS_REQUIRED = "訂單狀態不能為空";
    public static final String ORDER_WAREHOUSE_ID_REQUIRED = "出貨倉庫不能為空";
    public static final String ORDER_WAREHOUSE_ID_POSITIVE = "出貨倉庫 ID 必須大於 0";
    public static final String ORDER_ASSIGNED_VEHICLE_ID_POSITIVE = "指派車輛 ID 必須大於 0";
    public static final String ORDER_ASSIGNED_DRIVER_ID_POSITIVE = "指派司機 ID 必須大於 0";
    public static final String ORDER_SEQUENCE_POSITIVE = "配送順序必須大於 0";
    public static final String ORDER_BATCH_ROWS_REQUIRED = "訂單清單不能為空";

    // Driver delivery operation
    public static final String DELIVERY_ORDER_ID_REQUIRED = "訂單 ID 不能為空";
    public static final String DELIVERY_ORDER_ID_POSITIVE = "訂單 ID 必須大於 0";
    public static final String DELIVERY_BOX_COUNT_REQUIRED = "實際交貨箱數不能為空";
    public static final String DELIVERY_BOX_COUNT_MIN = "實際交貨箱數至少為 1";
    public static final String DELIVERY_NOTES_MAX_LENGTH = "交貨備註不能超過 500 字元";
    public static final String DELIVERY_ALREADY_ARRIVED = "這張訂單已經登記抵達門市";
    public static final String DELIVERY_ARRIVE_STATUS_INVALID = "目前訂單狀態不能登記抵達";
    public static final String DELIVERY_DELIVER_STATUS_INVALID = "訂單尚未登記抵達，不能完成交貨";
    public static final String DELIVERY_NO_SIGNATURE_STATUS_INVALID = "訂單尚未登記抵達，不能回報無人簽收";
    public static final String DELIVERY_BOX_COUNT_MISMATCH = "實際交貨箱數與訂單箱數不一致，預期 %d 箱";
    public static final String DELIVERY_ORDER_NOT_FOUND = "找不到訂單，ID：%d";
    public static final String DELIVERY_ROUTE_REQUIRED = "訂單尚未排入配送路線";
    public static final String DELIVERY_ROUTE_NOT_FOUND = "找不到訂單所屬路線，ID：%d";
    public static final String DELIVERY_ROUTE_NOT_PUBLISHED = "訂單所屬路線尚未發布";
    public static final String DELIVERY_TODAY_ONLY = "只能操作今天的配送任務";
    public static final String DELIVERY_WRONG_DRIVER = "這張訂單不屬於目前登入的司機";
    public static final String DELIVERY_DRIVER_MISMATCH = "訂單指派司機與路線司機不一致";
    public static final String DELIVERY_DRIVER_NOT_FOUND = "找不到司機，ID：%d";
    public static final String DELIVERY_DRIVER_INACTIVE = "司機帳號目前未啟用";
    public static final String DELIVERY_NOT_ARRIVED = "訂單尚未登記抵達門市";
    public static final String DELIVERY_ALREADY_FINISHED = "這次配送已經完成交貨或回報無人簽收";
    public static final String DELIVERY_STATUS_SUFFIX = "，目前狀態：%s";
    public static final String DELIVERY_NO_SIGNATURE_DESCRIPTION = "門市無人簽收";

    // Template（常配編組）
    public static final String TEMPLATE_NAME_REQUIRED = "編組名稱不能為空";
    public static final String TEMPLATE_NAME_MAX_LENGTH = "編組名稱不能超過 100 字元";
    public static final String TEMPLATE_NOTES_MAX_LENGTH = "備註不能超過 500 字元";
    public static final String TEMPLATE_ROUTES_REQUIRED = "編組至少要有一條路線";
    public static final String TEMPLATE_WAREHOUSE_ID_REQUIRED = "倉庫不能為空";
    public static final String TEMPLATE_VEHICLE_ID_REQUIRED = "車輛不能為空";
    public static final String TEMPLATE_STORE_IDS_REQUIRED = "路線至少要有一個停靠門市";

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
    public static final String VEHICLE_WAREHOUSE_ID_REQUIRED = "所屬倉庫 ID 不能為空";
    public static final String VEHICLE_WAREHOUSE_ID_MIN = "所屬倉庫 ID 必須大於 0";
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
    public static final String WAREHOUSE_PHONE_MAX_LENGTH = "電話長度不能超過 30 字元";
}
