-- 週日公定公休：把已經存進去、還沒到的週日班次改成休假。
--
-- 為什麼有這些資料：這條規則寫進 DriverScheduleService 之前，建立月班表會把每一天（含週日）建成 UNASSIGNED，
-- 新司機到職則把之後每一天（含週日）預設成 WORK。後台班表把週日當固定公休、格子不能編輯，
-- 所以這些週日主管在畫面上改不掉：UNASSIGNED 會擋住班表發布，WORK 會讓司機週日被當成可派。
--
-- 只改 2026-09-30（寫這支 migration 當天）以後的：過去的週日如果有人真的上班、打過卡，改掉出勤報表會對不上。
-- 日期寫死不用 CURDATE()：每個環境套用的日子不同，寫死才會每個資料庫改到同一批資料。
--
-- 不動 LEAVE：週日的請假背後有核准過的請假單（driver_leave_requests.driver_shift_id），
-- 改成休假會讓請假單指到一筆休假；請假當天一樣不會被派車，留著沒有影響。
--
-- DAYOFWEEK()：1 是週日。version + 1：讓還開著舊畫面的後台存檔時被樂觀鎖擋下，而不是蓋回去。
UPDATE driver_shifts
SET shift_type       = 'DAY_OFF',
    work_start       = NULL,
    work_end         = NULL,
    change_reason    = '週日公定公休',
    last_modified_at = NOW(6),
    version          = version + 1
WHERE DAYOFWEEK(work_date) = 1
  AND work_date >= '2026-09-30'
  AND shift_type IN ('UNASSIGNED', 'WORK');
