# logistics-dispatch

物流調度系統——服務於內部團購／門市配送，只負責物流執行，不處理商品銷售與商店端流程。

系統定位：**決策輔助系統**，排車演算法／AI 只產出建議，最終排定與發布權在後台人員。

## 技術棧

| 層                 | 選擇                        |
| ------------------ | --------------------------- |
| 前端               | Angular                     |
| 後端               | Java + Spring Boot + Gradle |
| 排車演算法         | Google OR-Tools（Java）     |
| 距離矩陣／路徑幾何 | OSRM（自架，台灣路網）      |
| 路網資料           | OpenStreetMap（台灣）       |
| 地圖顯示           | Leaflet.js                  |
| 地址轉座標         | Nominatim（公共服務）       |
| 拖曳看板           | Angular CDK DragDrop        |
| 報表圖表           | Chart.js                    |

全開源、無需 API Key、無需綁定信用卡；OSRM／OR-Tools／Leaflet 皆可離線運作，僅 Nominatim（低頻、非硬依賴）走公共服務。

## Repo 結構

```
logistics-dispatch/
├── docs/
│   ├── data-model.md      資料模型定義
│   ├── api-contract.md    API 契約
│   └── roadmap.md         時程與分工
├── frontend/               Angular
├── backend/                Spring Boot（Gradle），含排車引擎模組
├── osrm/                   OSRM 設定與啟動說明（資料檔不進版控）
├── seed/                   測試資料產生腳本
└── .gitignore
```

## 環境需求

- Java 17 或 21
- Node.js（Angular CLI 對應版本）
- Docker（跑 OSRM 用）
- IDEA（Ultimate／Professional 版才有 Angular CLI 專案精靈，Community 版改用內建 Terminal 執行指令）

## 快速開始

```bash
git clone git@github.com:xinchafu-jin/logistics-dispatch.git
cd logistics-dispatch
```

### 後端

```bash
cd backend
./gradlew build
./gradlew bootRun
```

第一次加完 OR-Tools 依賴後，務必先跑載入測試確認 native library 沒問題：

```java
Loader.loadNativeLibraries();
System.out.println("OR-Tools 載入成功");
```

### 前端

```bash
cd frontend
npm install
ng serve
```

### OSRM

見 [`osrm/README.md`](./osrm/README.md)，需先下載台灣路網資料並用 Docker 預處理，第一次設定約需半天。

## 開發流程

### 分支規則

- `main` 保護，任何人不直接 push
- 開發一律走 `feature/xxx` 分支 → 開 PR → **至少一人 review 過**才能 merge
- **9/11 起功能凍結**，`main` 只接受修 bug 的 PR

### 每個人的日常循環

```bash
git checkout main
git pull                          # 開工前先拿最新的

git checkout -b feature/你的任務

# ...開發...

git add .
git commit -m "說明做了什麼"
git push -u origin feature/你的任務
```

推上去後在 GitHub 開 PR，`base: main ← compare: feature/你的任務`，review 通過、確認能跑起來再 merge。

### 注意事項

- **AI 產生的程式碼，merge 前至少跑起來看過一次**，不要「看起來對就 merge」
- API 契約（`docs/api-contract.md`）異動時在群組講一聲，不要默默改
- 不架 CI/CD、不追求完整測試覆蓋率——五週的專題時間有限，力氣留給核心功能

## 文件索引

- [`docs/data-model.md`](./docs/data-model.md) — 資料模型
- [`docs/api-contract.md`](./docs/api-contract.md) — API 契約
- [`docs/roadmap.md`](./docs/roadmap.md) — 開發階段、時程、分工
