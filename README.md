# logistics-dispatch

物流調度系統——服務於內部團購／門市配送，只負責物流執行，不處理商品銷售與商店端流程。

系統定位：**決策輔助系統**，排車演算法／AI 只產出建議，最終排定與發布權在後台人員。

## 線上環境

| | 網址 | 測試帳號 |
|---|---|---|
| 後台（調度） | https://dispatch.xinchafujin.com | 帳號 `admin`，密碼 `Dispatch` |
| 司機端（PWA） | https://driver.xinchafujin.com | 帳號 `D001`～`D008` 任選一組，密碼皆為 `driver123` |

示範用環境，請勿輸入真實個資。司機端是手機介面，用電腦開的話請在瀏覽器開發者工具切到手機尺寸。

## 部署

單台 GCE VM（e2-medium / Debian 12），Docker Compose 編排四個服務，
Nginx 以同源反向代理串接前後端，兩個子網域各自服務後台與司機端：

```
瀏覽器 ─HTTPS→ Nginx ─┬─ dispatch.  → 後台（Angular）
                      ├─ driver.    → 司機端（Angular PWA）
                      └─ /api/      → Spring Boot ─┬─ MySQL（Flyway 管 schema）
                                                   └─ OSRM（全台路網，MLD）
```

CI/CD（GitHub Actions）：

- **CI**（`ci.yml`）：push 到 `main` 以外的分支時跑。後端起一顆空白 MySQL 8.4，
  由 Flyway 從 V1 套到最新（驗證 migration 能從頭套完），再跑整合測試；
  兩個前端各跑 `ng test` 與 production build。
- **CD**（`build-images.yml`）：push 到 `main` → 先跑一次 CI，**沒過就不建映像、不部署**
  → 建置 `linux/amd64` 映像推上 ghcr.io → SSH 進 VM 拉取並重啟 → 健康檢查。
  前端映像**必須在 CI 建置**，Angular 的 AOT 編譯器單一 worker 就需要 2GB 以上記憶體，
  4GB 的 VM 會 OOM。

完整步驟、決策理由與踩過的坑（Spring Boot 4 的 Flyway 模組拆分、
同源仍會被 CORS 擋、MySQL 8.4 的 Public Key Retrieval 等）
見 [docs/deployment.md](docs/deployment.md)。

### 本機開發

後端用 IDE 啟動、前端用 `ng serve`、OSRM 用 Docker 單獨跑。
`deploy/docker-compose.yml` 是**正式環境**用的，本機直接 `up` 會因為
缺少 Let's Encrypt 憑證而導致 web 容器啟動失敗，這是預期行為。

## 技術棧

| 層                 | 選擇                                              |
| ------------------ | ------------------------------------------------- |
| 前端               | Angular 22 + Angular Material（後台、司機端 PWA） |
| 後端               | Java 21 + Spring Boot 4 + Gradle                  |
| 資料庫             | MySQL 8.4，Flyway 管 schema                       |
| 排車演算法         | Google OR-Tools（Java）                           |
| 距離矩陣／路徑幾何 | OSRM（自架，台灣路網）                            |
| 路網資料           | OpenStreetMap（台灣）                             |
| 地圖顯示           | MapLibre GL JS                                    |
| 地址轉座標         | Nominatim（公共服務）                             |
| 拖曳看板           | Angular CDK DragDrop                              |
| 即時推播           | WebSocket（STOMP）                                |
| 報表               | 自繪圖表 + SheetJS 匯出 Excel                     |
| AI 調度助理        | Spring AI（OpenAI 相容 API，選用）                |
| 測試／CI           | JUnit、Vitest、GitHub Actions                     |

核心功能（排車、路網、地圖、報表）全開源、不需要 API Key；OSRM／OR-Tools／MapLibre 都可自架，
僅 Nominatim（低頻、非硬依賴）走公共服務。AI 調度助理是選用功能，需自備 OpenAI 相容的 API Key。

## Repo 結構

```
logistics-dispatch/
├── backend/           Spring Boot（Gradle），含排車引擎與 Flyway migration
├── frontend/          Angular 後台（調度看板、訂單審核、人車資源、報表）
├── frontend-driver/   Angular 司機端 PWA（出勤、出車前檢查、倉庫點交、導航）
├── deploy/            正式環境的 Docker Compose、Nginx 設定與 web 映像
├── osrm/              OSRM 設定與啟動說明（資料檔不進版控）
├── docs/              資料模型、API 契約、部署流程與各功能設計文件
├── scripts/           資料庫對齊、示範資料與 UI 驗證腳本
├── http/              IDE HTTP Client 請求範例
├── gps-api-tester/    獨立的 GPS API 測試工具（Next.js）
└── .github/           CI/CD（ci.yml、build-images.yml）與 CI 用的基礎資料
```

## 環境需求

- Java 21
- Node.js 22
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
- CI 會在每次 push 自動跑測試與 production build，紅燈先修再合併；`main` 上 CI 沒過就不會部署

## 文件索引

- [`docs/data-model.md`](./docs/data-model.md) — 資料模型
- [`docs/api-contract.md`](./docs/api-contract.md) — API 契約
- [`docs/roadmap.md`](./docs/roadmap.md) — 開發階段、時程、分工
- [`docs/deployment.md`](./docs/deployment.md) — 部署流程、CI/CD 與踩過的坑
