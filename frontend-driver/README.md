# 捷流智慧物流 - 司機端

司機使用的獨立 Angular 網站。登入與帳號驗證由後端 `http://localhost:8080` 提供。

## 啟動

```bash
npm install
npm start -- --port 4204
```

開啟 `http://localhost:4204/login`，使用物流後台建立的司機帳號登入。

## 驗證

```bash
npm test -- --watch=false
npm run build
```
