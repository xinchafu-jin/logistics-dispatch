#!/usr/bin/env python3
"""
產生 demo 用的歷史測試資料，輸出 SQL 到 stdout。

    python3 seed/gen-demo-data.py > /tmp/demo-data.sql
    mysql -u root -proot --default-character-set=utf8mb4 logistics < /tmp/demo-data.sql

補的東西：
  1. 8 月與 9 月的整月班表（平日 WORK、週末 DAY_OFF），讓司機任何一天都打得了卡
  2. 歷史訂單 + 已完成的路線（8/20–9/2 平日），讓報表有趨勢與完成率可算
  3. 對應的里程紀錄，讓「計畫 vs 實際」有實際值可比

刻意不碰的：
  - 今天（TODAY）的訂單與路線 —— 那是目前能跑的狀態
  - delivery_records / exception_cases / distance_matrix_cache
    這三張沒有 DAO，後端不讀也不寫，塞了只是死資料，還會跟訂單狀態互相矛盾

可重複執行：歷史資料都用 DO-H- 前綴與日期範圍圈起來，每次執行先刪再建。
"""
import datetime as dt
import random

TODAY = dt.date.today()                          # 跟著執行當天走，不寫死
HIST_FROM = TODAY - dt.timedelta(days=21)        # 往回三週，足夠讓報表有趨勢
HIST_TO = TODAY - dt.timedelta(days=1)           # 到昨天為止
# 上個月與這個月（HIST_FROM 可能落在上個月，班表要蓋得到）
SCHEDULE_MONTHS = sorted({HIST_FROM.replace(day=1), TODAY.replace(day=1)})

DRIVERS = list(range(1, 9))                      # 1..8
# vehicle_id -> (warehouse_id, capacity)
VEHICLES = {1: (1, 40), 2: (1, 60), 3: (1, 30), 4: (2, 40), 5: (2, 25)}
# warehouse_id -> 這一倉平常服務的門市
WAREHOUSE_STORES = {1: [1, 2, 3, 7, 8, 9, 10], 2: [1, 4, 5, 6]}
VENDORS = ["商家甲", "商家乙", "商家丙", "商家丁", "商家戊"]
ITEMS = ["民生用品", "飲料", "清潔用品", "生鮮", "冷凍食品"]

random.seed(20260921)                            # 固定種子，每次產出一樣

out = []
w = out.append


def q(s):
    return "'" + str(s).replace("'", "''") + "'"


def workdays(start, end):
    d, days = start, []
    while d <= end:
        if d.weekday() < 5:                      # 一~五
            days.append(d)
        d += dt.timedelta(days=1)
    return days


w("-- 由 seed/gen-demo-data.py 產生，請勿手改")
w("SET FOREIGN_KEY_CHECKS = 1;")
w("USE logistics;")
w("")

# ── 1. 清掉上次產生的歷史資料（orders 先於 routes，外鍵是 RESTRICT）──
w("-- ── 清除前一次產生的歷史資料 ──")
w(f"DELETE FROM orders WHERE order_number LIKE 'DO-H-%';")
w(f"DELETE FROM mileage_logs WHERE `date` BETWEEN {q(HIST_FROM)} AND {q(HIST_TO)};")
w(f"DELETE FROM routes  WHERE `date` BETWEEN {q(HIST_FROM)} AND {q(HIST_TO)};")
w("")

# ── 2. 月班表 ──
w("-- ── 月班表：8 月與 9 月，皆為已發布 ──")
for m in SCHEDULE_MONTHS:
    w(f"INSERT IGNORE INTO schedule_months (schedule_month, status, generated_at, published_at, version) "
      f"VALUES ({q(m)}, 'PUBLISHED', NOW(), NOW(), 0);")
w("")

w("-- ── 當日班次：平日上班、週末休假；已存在的日期不覆蓋 ──")
for m in SCHEDULE_MONTHS:
    last = (m.replace(day=28) + dt.timedelta(days=4)).replace(day=1) - dt.timedelta(days=1)
    d = m
    while d <= last:
        shift = "WORK" if d.weekday() < 5 else "DAY_OFF"
        for drv in DRIVERS:
            times = ("d.work_start, d.work_end" if shift == "WORK" else "NULL, NULL")
            w(f"INSERT IGNORE INTO driver_shifts "
              f"(schedule_month_id, driver_id, work_date, shift_type, work_start, work_end, "
              f" overtime_minutes, change_reason, last_modified_at, version) "
              f"SELECT sm.id, d.id, {q(d)}, {q(shift)}, {times}, 0, "
              f"{q('系統建立班表') if shift == 'WORK' else q('主管排定休假')}, NOW(), 0 "
              f"FROM drivers d CROSS JOIN schedule_months sm "
              f"WHERE d.id = {drv} AND sm.schedule_month = {q(m)};")
        d += dt.timedelta(days=1)
w("")

# ── 3. 歷史訂單與路線 ──
w("-- ── 歷史訂單、路線與里程（每個平日一批）──")
order_seq = 0
for day in workdays(HIST_FROM, HIST_TO):
    tag = day.strftime("%Y%m%d")
    # 當天出勤的司機：每天輪替，避免每天都是同樣三個人
    pool = DRIVERS[:]
    random.shuffle(pool)
    driver_for_vehicle = {v: pool[i] for i, v in enumerate(VEHICLES)}

    for vid, (wh, cap) in VEHICLES.items():
        # 有些日子某台車沒出門，讓資料不要太整齊
        if random.random() < 0.18:
            continue

        drv = driver_for_vehicle[vid]
        stops = random.sample(WAREHOUSE_STORES[wh], k=random.randint(3, min(5, len(WAREHOUSE_STORES[wh]))))
        boxes = []
        remaining = int(cap * random.uniform(0.72, 0.96))
        for i in range(len(stops)):
            take = max(3, remaining // (len(stops) - i)) if i < len(stops) - 1 else remaining
            take = min(take, remaining)
            boxes.append(take)
            remaining -= take
        loaded = sum(boxes)
        # 計畫里程：站數 × 每站平均，加上往返倉庫
        planned = round(len(stops) * random.uniform(4200, 7800) + random.uniform(6000, 11000))

        w(f"INSERT INTO routes (`date`, warehouse_id, vehicle_id, driver_id, template_id, "
          f"total_distance, estimated_fuel_cost, estimated_work_minutes, load_rate, status, version) "
          f"VALUES ({q(day)}, {wh}, {vid}, {drv}, NULL, {planned}, NULL, NULL, "
          f"{round(loaded / cap, 4)}, 'PUBLISHED', 1);")
        w(f"SET @rid = LAST_INSERT_ID();")

        for seq, (store, box) in enumerate(zip(stops, boxes), start=1):
            order_seq += 1
            # 多數完成，少量失敗或取消，讓報表的狀態分佈不是 100%
            roll = random.random()
            status = "COMPLETED" if roll < 0.90 else ("FAILED" if roll < 0.96 else "CANCELLED")
            num = f"DO-H-{tag}-{order_seq:04d}"
            w(f"INSERT INTO orders (order_number, store_id, warehouse_id, source_vendor, "
              f"item_description, box_count, notes, delivery_date, status, route_id, "
              f"assigned_vehicle_id, assigned_driver_id, `sequence`, created_at, updated_at) "
              f"VALUES ({q(num)}, {store}, {wh}, {q(random.choice(VENDORS))}, "
              f"{q(random.choice(ITEMS))}, {box}, NULL, {q(day)}, {q(status)}, @rid, "
              f"{vid}, {drv}, {seq}, {q(day)} + INTERVAL 7 HOUR, {q(day)} + INTERVAL 19 HOUR);")

        # 里程：實際比計畫多 2%~14%（繞路、找車位），工時 7.5~10 小時
        actual_km = round(planned / 1000 * random.uniform(1.02, 1.14))
        start_odo = random.randint(48000, 132000)
        out_h, out_m = 8, random.randint(0, 25)
        work_min = random.randint(450, 600)
        w(f"INSERT INTO mileage_logs (driver_id, `date`, start_odometer, end_odometer, start_time, end_time) "
          f"VALUES ({drv}, {q(day)}, {start_odo}, {start_odo + actual_km}, "
          f"{q(day)} + INTERVAL {out_h * 60 + out_m} MINUTE, "
          f"{q(day)} + INTERVAL {out_h * 60 + out_m + work_min} MINUTE);")
    w("")

# ── 4. 檢查 ──
w("-- ── 結果檢查 ──")
w("SELECT '歷史訂單' AS item, COUNT(*) AS n FROM orders WHERE order_number LIKE 'DO-H-%'")
w("UNION ALL SELECT '歷史路線', COUNT(*) FROM routes WHERE `date` < CURDATE()")
w("UNION ALL SELECT '里程紀錄', COUNT(*) FROM mileage_logs")
w("UNION ALL SELECT '班次總數', COUNT(*) FROM driver_shifts")
w("UNION ALL SELECT '今日訂單', COUNT(*) FROM orders WHERE delivery_date = CURDATE()")
w("UNION ALL SELECT '今日路線', COUNT(*) FROM routes WHERE `date` = CURDATE();")
w("")
w("SELECT status, COUNT(*) AS n FROM orders GROUP BY status ORDER BY n DESC;")

print("\n".join(out))
