# 三階段：分別建兩個 Angular，最後塞進 Nginx。
# build context 是專案根目錄，所以 COPY 路徑都要帶 frontend/ 前綴。
# 建置指令：docker build -f deploy/web/Dockerfile -t logistics-web .

# ---------- 後台 ----------
FROM node:22 AS admin
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --ignore-scripts
COPY frontend/ ./
RUN npx ng build

# ---------- 司機端 ----------
# 走獨立子網域 driver.xinchafujin.com，維持根路徑，不需要 --base-href
FROM node:22 AS driver
WORKDIR /app
COPY frontend-driver/package.json frontend-driver/package-lock.json ./
RUN npm ci --ignore-scripts
COPY frontend-driver/ ./
RUN npx ng build

# ---------- Nginx ----------
FROM nginx:alpine
COPY --from=admin  /app/dist/logistics-dispatch-frontend/browser/ /var/www/admin/
COPY --from=driver /app/dist/frontend-driver/browser/             /var/www/driver/
COPY deploy/web/default.conf /etc/nginx/conf.d/default.conf
EXPOSE 80