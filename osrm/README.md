# osrm

OSRM 路徑服務的設定與啟動說明。

資料檔（`.osm.pbf`、`.osrm*`）**不進版本控制**，每個人在自己機器上照下方步驟下載與預處理，因為原始資料約 200MB、預處理後好幾 GB，不適合放進 repo。

## 1. 下載台灣路網資料

```bash
cd osrm
wget https://download.geofabrik.de/asia/taiwan-latest.osm.pbf
```

## 2. 預處理（用 Docker，只需跑一次）

```bash
docker run -t -v "${PWD}:/data" ghcr.io/project-osrm/osrm-backend osrm-extract -p /opt/car.lua /data/taiwan-latest.osm.pbf

docker run -t -v "${PWD}:/data" ghcr.io/project-osrm/osrm-backend osrm-partition /data/taiwan-latest.osrm

docker run -t -v "${PWD}:/data" ghcr.io/project-osrm/osrm-backend osrm-customize /data/taiwan-latest.osrm
```

預處理時間約 5～15 分鐘，記憶體需求約 4～8GB。

## 3. 啟動服務

```bash
docker run -t -i -p 5000:5000 -v "${PWD}:/data" ghcr.io/project-osrm/osrm-backend osrm-routed --algorithm mld /data/taiwan-latest.osrm
```

啟動後可用 `http://localhost:5000` 存取。

## 常用端點

| 端點 | 用途 |
|---|---|
| `/table/v1/driving/{經緯度清單}` | 一次取得距離／時間矩陣，餵給 OR-Tools |
| `/route/v1/driving/{起點};{終點}?geometries=geojson` | 取得路徑幾何，交給 Leaflet 畫線 |

## 注意事項

- demo 前記得先啟動這個服務，backend 會呼叫 `localhost:5000`
- 門市座標固定，距離矩陣建議算一次後存入資料庫（見 `docs/data-model.md` 的 `DistanceMatrixCache`），只有新增／修改門市座標時才需要觸發重算
- 開發初期若 OSRM 還沒架好，可先用直線距離頂著讓 OR-Tools 跑通，之後再抽換底層實作（見 `docs/api-contract.md` 距離矩陣相關端點）
