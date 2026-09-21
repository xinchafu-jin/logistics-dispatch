# 部署流程

作品集 / 面試 demo 用途的正式環境建置手冊。網域 `xinchafujin.com`，單台 GCE VM。

## 架構

```
dispatch.xinchafujin.com ─┐
driver.xinchafujin.com   ─┴─→ Nginx :443 ─┬─→ /var/www/admin   （後台靜態檔）
                                           ├─→ /var/www/driver  （司機端 PWA）
                                           └─→ 127.0.0.1:8080   （Spring Boot）
                                                    ├─→ 127.0.0.1:3306 MySQL
                                                    └─→ 127.0.0.1:5001 OSRM
```

兩個子網域**各自都設 `/api/` 反向代理**到同一個後端，所以兩邊都是同源，
前端的相對路徑 `/api/...` 與 interceptor 的 `startsWith('/api/')` 判斷完全不用改。

| 決策 | 選擇 | 理由 |
|---|---|---|
| 機型 | e2-medium（2vCPU/4GB） | 搭配縮小路網足夠，月費約為 8GB 機型的一半 |
| 磁碟 | pd-balanced 30GB | SSD 貴一倍，這用量不需要 |
| 司機端 | **獨立子網域**，不走 `/driver/` 子路徑 | 避開 service worker scope 風險，且不必 `--base-href` |
| 資料庫 | VM 自架 MySQL + 每日 dump | demo 用途，Cloud SQL 的月費不划算 |
| schema | Flyway | 目前靠人工同步 15 張表，正式機不能砍掉重建 |

---

# 階段零：程式改動

這些沒做完不要開始佈署。

## 0-1 導入 Flyway（最重要）

`build.gradle`：

```gradle
implementation 'org.springframework.boot:spring-boot-flyway'
implementation 'org.flywaydb:flyway-mysql'
```

> **Spring Boot 4 的陷阱**：不能只加 `org.flywaydb:flyway-core`。
> Boot 4 把 autoconfiguration 拆成獨立模組，Flyway 的自動組態在
> `spring-boot-flyway` 裡。只加 `flyway-core` 的話 jar 會在 classpath 上，
> 但**沒有任何東西去啟動它**——應用程式正常啟動、完全不報錯、
> 資料表一張都不會建。這個失敗模式非常安靜，很難聯想到是依賴少了一個。

建立 `backend/src/main/resources/db/migration/V1__baseline.sql`，
內容取自 `seed/schema.sql`，但：

> **必須刪掉全部 18 個 `DROP TABLE` 敘述。**
> `seed/schema.sql` 是「先刪後建」的本機重置腳本，
> 直接在正式機執行等於把所有資料清空。

之後每次改 entity 欄位就新增 `V2__xxx.sql`、`V3__xxx.sql`，不要改動已套用過的檔案。

## 0-2 application.properties 環境變數化

```properties
spring.datasource.url=${DB_URL}
spring.datasource.username=${DB_USER}
spring.datasource.password=${DB_PASSWORD}
app.osrm.base-url=${OSRM_BASE_URL:http://localhost:5001}
spring.jpa.show-sql=${SHOW_SQL:false}
management.endpoints.web.exposure.include=health
```

DB 帳密目前寫死在檔案且已進 git，正式機必須能用環境變數覆寫。
`show-sql=true` 上線會把 log 灌爆。

## 0-3 移除 crypto 預設值

`SecurityConfig.java` 的 `aiApiKeyEncryptor` 有 `todo 暫時加預設啟動`，
正式環境改成必填（移除 `:預設值`）。漏設環境變數時會安靜地用開發預設值加密，
比啟動失敗更難查。

## 0-4 準備三組 secret

```bash
openssl rand -base64 48   # JWT_SECRET
openssl rand -base64 36   # APP_CRYPTO_PASSWORD
openssl rand -hex 8       # APP_CRYPTO_SALT（16 位 hex）
```

> `APP_CRYPTO_PASSWORD` / `APP_CRYPTO_SALT` **一旦上線就不能再換**，
> 換了資料庫裡存的 AI API key 全部解不開。第一次就要定好並備份。

---

# 階段一：DNS（先做，因為要等傳播）

## 1-1 保留靜態 IP（不必先開 VM）

```bash
gcloud compute addresses create logistics-ip --region=asia-east1
gcloud compute addresses describe logistics-ip --region=asia-east1 --format='value(address)'
```

## 1-2 設三筆 A 記錄

在網域商的 DNS 管理介面，全部指向上面那個 IP：

| 類型 | 名稱 | 值 |
|---|---|---|
| A | `@` | `<靜態 IP>` |
| A | `dispatch` | `<靜態 IP>` |
| A | `driver` | `<靜態 IP>` |

用 Cloudflare 的話，**三筆都要切成灰雲（DNS only）**。
橘雲代理狀態下 Let's Encrypt 的 HTTP-01 驗證會被攔截，certbot 會失敗且訊息難懂。

## 1-3 確認生效

```bash
dig +short dispatch.xinchafujin.com
dig +short driver.xinchafujin.com
```

都要回傳你的 IP。**這步沒過就不要跑 certbot。**

DNS 傳播期間可以直接往下做階段二。

---

# 階段二：VM 建置

## 2-1 開 VM

| 項目 | 值 |
|---|---|
| 機型 | `e2-medium`（2vCPU/4GB） |
| 映像 | **Debian 12**（不要 Alpine，OR-Tools native lib 依賴 glibc） |
| 磁碟 | `pd-balanced` 30GB |
| 外部 IP | 選前面保留的 `logistics-ip` |
| 網路標籤 | **`http-server`、`https-server`** |

網路標籤不能漏。GCE 預設防火牆規則靠標籤生效，沒貼等於 80/443 沒開——
這是最常見的「都裝好了卻連不到」原因。

```bash
gcloud compute instances add-tags <VM名稱> --tags=http-server,https-server --zone=<區域>
```

## 2-2 裝環境

```bash
sudo apt update
sudo apt install -y openjdk-21-jdk nginx mysql-server docker.io rsync osmium-tool
sudo systemctl enable --now docker nginx mysql
```

## 2-3 建資料庫

```bash
sudo mysql
```
```sql
CREATE DATABASE logistics CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER 'logistics'@'localhost' IDENTIFIED BY '<強密碼>';
GRANT ALL PRIVILEGES ON logistics.* TO 'logistics'@'localhost';
FLUSH PRIVILEGES;
```

**不要手動匯入 `seed/schema.sql`**，表結構交給 Flyway 在後端第一次啟動時建立。

## 2-4 OSRM（縮小路網以省記憶體）

全台路網要 2–3GB 記憶體，demo 用不到。切一塊測試資料涵蓋的範圍即可，
記憶體可降到數百 MB，這是能用 4GB 機型的關鍵。

```bash
sudo mkdir -p /opt/osrm && cd /opt/osrm
sudo wget https://download.geofabrik.de/asia/taiwan-latest.osm.pbf

# 切出台南範圍（bbox：西,南,東,北）
# seed-data.sql 的實際座標範圍：經度 120.16~120.33、緯度 22.96~23.12，
# 這裡各留約 0.1 度邊界，讓路徑規劃有繞路空間。
sudo osmium extract -b 120.05,22.85,120.45,23.25 \
  taiwan-latest.osm.pbf -o demo.osm.pbf

sudo docker run -t -v /opt/osrm:/data ghcr.io/project-osrm/osrm-backend osrm-extract -p /opt/car.lua /data/demo.osm.pbf
sudo docker run -t -v /opt/osrm:/data ghcr.io/project-osrm/osrm-backend osrm-partition /data/demo.osrm
sudo docker run -t -v /opt/osrm:/data ghcr.io/project-osrm/osrm-backend osrm-customize /data/demo.osrm
```

bbox 必須涵蓋 `seed-data.sql` 裡所有門市與倉庫的座標，否則路徑會算不出來。
測試資料是**台南地區**（2 個倉庫、10 間門市、5 台車、3 位司機、21 張訂單），不是雙北。
之後要換全台，把 `demo` 換回 `taiwan-latest` 重跑即可，程式端無須改動。

常駐啟動：

```bash
sudo docker run -d --name logistics-osrm --restart unless-stopped \
  -p 127.0.0.1:5001:5000 -v /opt/osrm:/data \
  ghcr.io/project-osrm/osrm-backend osrm-routed --algorithm mld /data/demo.osrm
```

`-p` 前面的 `127.0.0.1:` 不能省，否則 OSRM 會對整個網際網路公開。

## 2-5 佈署帳號與目錄

```bash
sudo useradd -m -s /bin/bash deploy
sudo mkdir -p /var/www/admin /var/www/driver /opt/app
sudo chown -R deploy:deploy /var/www /opt/app
sudo mkdir -p /home/deploy/.ssh && sudo chmod 700 /home/deploy/.ssh
# 把 CI 用的 public key 寫進 /home/deploy/.ssh/authorized_keys
sudo chown -R deploy:deploy /home/deploy/.ssh
```

允許 deploy 免密碼重啟後端（`sudo visudo -f /etc/sudoers.d/deploy`）：

```
deploy ALL=(ALL) NOPASSWD: /bin/systemctl restart backend, /bin/systemctl status backend
```

## 2-6 環境變數檔

`/opt/app/.env`：

```
DB_URL=jdbc:mysql://localhost:3306/logistics?serverTimezone=Asia/Taipei&useSSL=false&rewriteBatchedStatements=true
DB_USER=logistics
DB_PASSWORD=<階段 2-3 設的密碼>
OSRM_BASE_URL=http://localhost:5001
JWT_SECRET=<階段 0-4 產生的>
APP_CRYPTO_PASSWORD=<階段 0-4 產生的，不可再更改>
APP_CRYPTO_SALT=<階段 0-4 產生的，不可再更改>
AI_API_KEY=
SHOW_SQL=false
```

```bash
sudo chown deploy:deploy /opt/app/.env && sudo chmod 600 /opt/app/.env
```

## 2-7 systemd

`/etc/systemd/system/backend.service`：

```ini
[Unit]
Description=Logistics Dispatch Backend
After=network.target mysql.service docker.service

[Service]
User=deploy
EnvironmentFile=/opt/app/.env
ExecStart=/usr/bin/java -Xmx1500m -jar /opt/app/backend.jar
SuccessExitStatus=143
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
```

4GB 機型上 `-Xmx1500m` 是保守值，要留空間給 MySQL 和 OSRM。

## 2-8 Nginx

`/etc/nginx/sites-available/logistics`：

```nginx
# 後台
server {
    listen 80;
    server_name dispatch.xinchafujin.com xinchafujin.com;

    root /var/www/admin;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /actuator/health {
        proxy_pass http://127.0.0.1:8080;
    }
}

# 司機端 PWA
server {
    listen 80;
    server_name driver.xinchafujin.com;

    root /var/www/driver;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    # service worker 不可被快取，否則司機端更新不了
    location = /ngsw.json {
        add_header Cache-Control "no-cache";
        try_files $uri =404;
    }
    location = /ngsw-worker.js {
        add_header Cache-Control "no-cache";
        try_files $uri =404;
    }

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

```bash
sudo ln -s /etc/nginx/sites-available/logistics /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t && sudo systemctl reload nginx
```

`try_files` 不能省，漏了使用者在內頁按 F5 就會 404。

## 2-9 HTTPS

確認階段 1-3 的 `dig` 已回傳正確 IP 再執行：

```bash
sudo apt install -y certbot python3-certbot-nginx
sudo certbot --nginx \
  -d xinchafujin.com \
  -d dispatch.xinchafujin.com \
  -d driver.xinchafujin.com
```

certbot 會自動改寫上面的 Nginx 設定加上 443 與憑證路徑，並設定自動續期。

司機端的 service worker **只在 HTTPS 下註冊**，這步沒做 PWA 功能等於沒有。

---

# 階段三：第一次上線

建議手動走一遍再自動化，你會知道每個環節在幹嘛，之後 CI 壞掉才修得動。

## 3-1 本機建置

```bash
cd backend && ./gradlew bootJar
cd ../frontend && npm ci && npx ng build
cd ../frontend-driver && npm ci && npx ng build
```

司機端走獨立子網域，**不需要 `--base-href`**，維持根路徑即可。

## 3-2 上傳

```bash
VM=deploy@dispatch.xinchafujin.com

scp backend/build/libs/backend-0.0.1-SNAPSHOT.jar $VM:/opt/app/backend.jar
rsync -az --delete frontend/dist/*/browser/          $VM:/var/www/admin/
rsync -az --delete frontend-driver/dist/*/browser/   $VM:/var/www/driver/
```

`ng build` 的輸出在 `dist/<專案名>/browser/`，來源要指到 `browser/` 那層，
指錯會把 `server/` 和 `index.csr.html` 一起丟上去。

## 3-3 啟動後端

```bash
ssh $VM 'sudo systemctl daemon-reload && sudo systemctl enable --now backend'
ssh $VM 'journalctl -u backend -f'
```

第一次啟動時 Flyway 會建立全部資料表。看到 `Started BackendApplication` 才算成功。
失敗的話 log 會寫清楚原因，通常是環境變數少一個或 MySQL 權限沒給對。

## 3-4 建立第一個管理員（必要步驟）

> `seed-data.sql` **沒有任何管理員資料**，而 `/api/admin-users/**` 需要 ADMIN 權限才能呼叫。
> 全新資料庫是「沒有管理員，也無法透過 API 建立管理員」的死結，
> 第一個帳號**只能手動用 SQL 塞進去**。

在本機產生 BCrypt 雜湊：

```bash
python3 -c "import bcrypt;print(bcrypt.hashpw(b'<你的強密碼>', bcrypt.gensalt()).decode())"
```

在 VM 上（欄位名請對照 `db/migration/V1__baseline.sql` 的 `admin_users` 定義）：

```sql
INSERT INTO admin_users (account, name, password, ...)
VALUES ('admin', '系統管理員', '<上面產生的雜湊>', ...);
```

## 3-5 灌 demo 資料

```bash
scp seed/seed-data.sql $VM:/tmp/
ssh $VM 'mysql -u logistics -p logistics < /tmp/seed-data.sql'
```

> `seed-data.sql` 的司機密碼明文是 `driver123`，且已寫在 repo 註解裡。
> demo 用途可以接受（本來就是給面試官試用的），
> 但**管理員密碼務必另外設強的**，不要沿用任何測試值。

## 3-6 驗收

```bash
curl -f https://dispatch.xinchafujin.com/actuator/health
curl -I https://dispatch.xinchafujin.com/
curl -I https://driver.xinchafujin.com/
```

瀏覽器實測清單：

- [ ] 後台能登入，地圖與調度看板正常
- [ ] 司機端能登入、能打卡
- [ ] **在內頁按 F5 不會 404**
- [ ] 網址列顯示鎖頭（HTTPS）
- [ ] 手機開司機端，瀏覽器出現「加到主畫面」（PWA 生效）
- [ ] 路徑規劃能算出路線（確認 OSRM 的 bbox 涵蓋測試資料）

---

# 本機 Docker 化的已知坑（實測記錄）

這些都是實際撞到並解決的，VM 上同樣適用。

## npm ci 的 ETXTBSY

```
npm error Error: spawnSync /app/node_modules/esbuild/bin/esbuild ETXTBSY
```

esbuild 寫完二進位檔立刻執行它，在 Docker 的檔案系統上會撞到 race。
解法是 `RUN npm ci --ignore-scripts`（esbuild 的實際執行檔由
`@esbuild/<platform>` 這個 optional dependency 提供，跳過 postinstall 不影響建置）。

## Angular 建置爆記憶體

```
✘ [ERROR] Worker terminated due to reaching memory limit: JS heap out of memory
```

Angular 的 AOT 編譯器單一 worker 就要 2GB 以上。兩件事：

1. 本機：Docker Desktop → Settings → Resources → Memory 至少 8GB（預設可能只有 2GB）
2. Dockerfile 的 node stage 加 `ENV NODE_OPTIONS=--max-old-space-size=4096`

> **這代表 e2-medium（4GB）的 VM 不可能建置前端。**
> 流程必須是「本機或 GitHub Actions 建好映像 → 推 registry → VM 只 pull」，
> 不能在 VM 上跑 `docker compose up --build`。

## 同源也會被 CORS 擋（最不直覺的一個）

症狀：頁面載得出來，**一登入就 403 `Invalid CORS request`**。

原因：瀏覽器對 **POST 一律送出 `Origin` 標頭，即使前後端同源**。
Spring Security 的 CORS 過濾器不區分同源與跨源，只要看到 `Origin`
就比對 `allowedOrigins`，比對不到就回 403。

所以 GET（載入頁面、靜態檔）正常，POST（登入、建單）全滅。

> 「走 Nginx 同源所以不用管 CORS」是錯的。
> 同源指的是**瀏覽器不強制執行** CORS 檢查，
> 但**伺服器端的過濾器照樣會處理** `Origin` 標頭——兩者是不同層次。

解法：把白名單改成可設定，正式環境用環境變數帶入實際網域。

```java
@Bean
public CorsConfigurationSource corsConfigurationSource(
        @Value("${app.cors.allowed-origins}") List<String> allowedOrigins) {
    configuration.setAllowedOrigins(allowedOrigins);
```

```yaml
# docker-compose.yml，逗號之間不能有空格
CORS_ALLOWED_ORIGINS: https://dispatch.xinchafujin.com,https://driver.xinchafujin.com,https://xinchafujin.com
```

驗證方式（帶 Origin 才測得出來）：

```bash
curl -s -o /dev/null -w "%{http_code}\n" -X POST https://driver.xinchafujin.com/api/auth/driver/login \
  -H "Content-Type: application/json" -H "Origin: https://driver.xinchafujin.com" \
  -d '{"account":"x","password":"x"}'
```

400（帳密錯誤）= 正常；403 = CORS 沒過。**不帶 Origin 測會看到 400 而誤判成沒問題。**

## MySQL 8.4 的 Public Key Retrieval

```
Unable to obtain connection from database: Public Key Retrieval is not allowed
```

MySQL 8.4 預設 `caching_sha2_password`，非加密連線下驅動要先索取公鑰而被拒。
解法是**把 JDBC URL 的 `useSSL=false` 拿掉**，Connector/J 預設 `sslMode=PREFERRED`
會走 TLS，沒有這個問題。比加 `allowPublicKeyRetrieval=true` 安全。

## mysql CLI 的中文顯示

查詢時沒帶 `--default-character-set=utf8mb4` 會顯示成 `??????`，
但資料本身是好的（用 `SELECT HEX(欄位)` 可驗證）。不要被騙去重灌資料。

---

# 階段四：CI/CD

分兩步，不要一次到位——一起上的話出問題分不清是佈署設定錯還是測試環境錯。

## 4-1 先做 CD

`.github/workflows/deploy.yml`，push 到 main 觸發。重點：

- 用 `paths` 過濾，改後端不要觸發前端建置
- 兩個前端的 lock file **分開快取**（`cache-dependency-path`）
- 佈署到帶時間戳的目錄再切 symlink，出事能一秒回滾
- 結尾一定要 `curl --retry 10 --retry-delay 3 -f .../actuator/health`，
  後端啟動要十幾秒，沒確認就當成功會誤判
- 需要的 GitHub Secrets：`VM_SSH_KEY`、`VM_HOST`

後端重啟期間會停機數十秒，demo 環境可接受。

## 4-2 再加 CI

現有測試：後端 JUnit 4 個、後台 spec 7 個、司機端 spec 3 個。

**已知障礙**：

- `AiApiKeyApiTest` 是完整 `@SpringBootTest` 且注入 `JdbcTemplate`，會真的讀寫資料庫；
  GitHub runner 沒有 MySQL 會直接失敗
- `backend/src/test/resources/` 是空的，測試會吃到 `application.properties` 的 `localhost:3306`
- `OsrmClientTest` 需確認是否真的打 `localhost:5001`

**解法**：CI 裡起 MySQL service container + 新增 `application-test.properties` 指過去。
或先用 `@Tag("integration")` 排除 DB 測試，之後再補。

---

# 上線後

## 備份（不能省）

`/opt/app/backup.sh`：

```bash
#!/bin/bash
set -e
D=$(date +%F)
mysqldump -u logistics -p"$DB_PASSWORD" logistics | gzip > /tmp/logistics-$D.sql.gz
gsutil cp /tmp/logistics-$D.sql.gz gs://<你的bucket>/db/
rm /tmp/logistics-$D.sql.gz
```

```
0 3 * * * /opt/app/backup.sh
```

VM 刪掉等於資料沒了，這是單機部署唯一無法自動救回的風險。

## 成本控管

- **GCP 帳單 → 預算與快訊，設 50% / 90% 門檻寄信**
- $300 試用額度 **90 天到期**，求職期通常更長；到期前要接上付款方式，
  否則 VM 會被停、面試官點進去是空白
- 靜態 IP 未綁在執行中的 VM 上會以較高費率計費，別保留了忘記用

## 面試用途的收尾

- README 最上面放**線上網址 + demo 帳密**，讓面試官三秒進得去
- 後台與司機端**各給一組測試帳號**
- 保留本文件，架構取捨（為何單機、為何同源、為何不用 Firebase）本身就是面試素材
