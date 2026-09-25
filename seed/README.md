# seed

本機測試資料（高雄地區：2 個倉庫、10 間門市、5 台車、8 位司機）。

## 從空資料庫重建

依序執行，缺一步後面會對不起來：

1. 啟動一次後端：Flyway 會把資料表建到最新版本（V1～最新）。
2. `seed-data.sql`：灌主檔，以及今天 21 張 CONFIRMED 訂單。
3. `gen-demo-data.py`：灌本月的已發布班表，以及過去三週已完成的歷史路線（報表才有趨勢）。
4. `today-routes.sql`：今天乾淨的起點。WH001 三條路線已發布，每張單都待點交，還沒有任何人動過。
5. （選用）`today-scenarios.sql`：在第 4 步之上加各種動作，包括點交、交貨、無人簽收、點交不符、出勤、GPS、聊天。

```bash
mysql -u root -p --default-character-set=utf8mb4 logistics < seed/seed-data.sql
python3 seed/gen-demo-data.py | mysql -u root -p --default-character-set=utf8mb4 logistics
mysql -u root -p --default-character-set=utf8mb4 logistics < seed/today-routes.sql
mysql -u root -p --default-character-set=utf8mb4 logistics < seed/today-scenarios.sql
```

司機帳號是 D001～D008，密碼都是 `driver123`。主管帳號不在 seed 裡，要另外建。

隔天要再用，重跑步驟 2～4 就好，不用刪庫。只想清掉今天做過的動作、回到乾淨起點，重跑第 4 步即可。

## 乾淨起點（today-routes.sql）

**WH001 楠梓物流中心**：已發布，司機打卡後就能點交。
- KH-1001 陳大明：鼓山 → 鹽埕 → 前鎮（兩張）→ 三民
- KH-1002 林志偉：左營 → 楠梓（兩張）→ 岡山（兩張）→ 路竹
- KH-1003 黃俊宏：左營 → 三民 → 鳳山 → 林園

**WH002 小港轉運站**：六張都待排車，可以直接測自動排車。

還沒有任何單開始配送，所以看板可以撤回。想從排車開始測，撤回後重排即可。

## 各種狀態（today-scenarios.sql）

**WH001**：
- 陳大明跑到一半：有完成、無人簽收、破損補送、交貨中、已點交。
- 林志偉還在倉庫點交：有已點交、點交不符、待點交。
- 黃俊宏已上班，四張都待點交。

**WH002**：三張待排車、兩張待總部確認、一張已取消。

**異常中心**：
- 今天看得到：林志偉的點交不符，以及上一個工作日的無人簽收（確認後，補送單會進待排車）。
- 陳大明的無人簽收與破損：隔天 06:00 才會進異常中心。

時間都是從執行當下往回推，請在白天執行。
