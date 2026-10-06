# Trading Platform（接手專案）

商品交易平台後端服務。**這是一份從前一位開發者手上接手的專案，目前「跑得起來」，但品質與正確性尚待檢視。**

## 技術棧
- Java 21 / Spring Boot 4.0
- Spring Security + JWT
- Spring Data JPA / PostgreSQL
- springdoc (Swagger UI)

## 啟動方式

1. 啟動資料庫：
   ```
   docker compose up -d
   ```
2. 啟動應用程式：
   ```
   ./mvnw spring-boot:run
   ```
   （或用 IDE 執行 `TradeApplication`）
3. Swagger UI： http://localhost:8080/swagger-ui.html

## 預設帳號
| 帳號 | 密碼 | 角色 |
|------|------|------|
| admin | admin123 | ADMIN |
| alice | alice123 | USER |

## 主要 API
- `POST /api/auth/login` 登入，回傳 JWT
- `GET/POST/PUT /api/products`、`DELETE /api/products/delete/{id}` 商品（新增／修改／刪除限 ADMIN）
- `GET /api/products/search` 商品進階查詢（見下方說明）
- `POST /api/orders`、`GET /api/orders` 下單與查詢

> 呼叫受保護 API 時，於 Header 帶入 `Authorization: Bearer <token>`。

## 商品進階查詢

```
GET /api/products/search?keyword=鍵盤&minPrice=1000&maxPrice=5000&sort=price&direction=asc&size=20&cursor=...
```

| 參數 | 說明 | 預設 |
|------|------|------|
| `keyword` | 名稱模糊搜尋，不分大小寫；`%`、`_`、`\` 視為一般文字。最多 100 字 | 不篩選 |
| `minPrice` / `maxPrice` | 價格區間，包含上下界 | 不篩選 |
| `sort` | `price`、`createdAt`、`id`（白名單） | `createdAt` |
| `direction` | `asc`、`desc` | `desc` |
| `size` | 1–100 | 20 |
| `cursor` | 上一頁回應的 `nextCursor`，第一頁不帶 | — |

回應：`{ "items": [...], "nextCursor": "...", "hasNext": true }`。參數不合法時回傳 400（ProblemDetail）。

### 設計重點

- **模糊搜尋**：`pg_trgm` GIN 索引建在 `lower(name)` 上，查詢條件同樣寫成 `lower(name) LIKE ?`，運算式一致才會用到索引。
- **排序**：只允許有 `(欄位, id)` 複合索引的欄位，並一律以 `id` 作為次要排序，同值資料的順序才固定。
- **keyset 分頁**：以上一頁最後一筆的 `(排序值, id)` 作為 cursor，條件寫成 `v >= c AND (v > c OR id > cid)`，讓 PostgreSQL 直接從索引定位起點。不使用 OFFSET（越後面越慢、翻頁途中資料異動會重複或遺漏），也不計算總筆數（百萬筆加上模糊條件的 `count(*)` 本身就要數十毫秒）。代價是無法直接跳到第 N 頁。
- **索引建立**：`pg_trgm` 擴充與 GIN 運算式索引無法由 Hibernate `ddl-auto` 建立，集中在 `src/main/resources/db/product-search-indexes.sql`。應用程式啟動、資料表建立後手動執行一次即可（重複執行不會出錯）：
  ```
  docker compose exec -T db psql -U trading -d trading < src/main/resources/db/product-search-indexes.sql
  ```
  沒有執行時搜尋功能仍然正確，只是會退化成全表掃描。整合測試以 `@Sql` 套用同一份腳本。

### 效能實測（100 萬筆）

PostgreSQL 16（Docker，macOS），`perf/seed-products.sql` 產生資料、`perf/search-benchmark.sql` 執行 `EXPLAIN ANALYZE`。數字為快取暖機後的單次執行時間，僅供數量級比較。

| 查詢 | 無索引 | 加索引後 | 執行計畫 |
|------|-------:|-------:|------|
| Q1 預設首頁（createdAt desc） | 22.2 ms | 0.08 ms | `(created_at, id)` 反向索引掃描 |
| Q2 關鍵字 4 字，命中 1.6% | 77.7 ms | 1.8 ms | 依排序索引掃描，邊掃邊過濾 |
| Q3 關鍵字 SKU，命中 1 筆 | 75.4 ms | 5.7 ms | trigram GIN |
| Q4 關鍵字 4 字，命中 100 筆 | 72.1 ms | 0.18 ms | trigram GIN |
| **Q5 關鍵字 2 字，命中 100 筆** | 74.2 ms | **144.9 ms** | 見下方「已知限制」 |
| Q6 價格區間 + 依價格排序 | 13.0 ms | 0.02 ms | `(price, id)` 範圍掃描 |
| Q7 關鍵字 2 字 + 價格區間 | 14.0 ms | 0.11 ms | `(price, id)` 範圍掃描 + 過濾 |
| Q8 OFFSET 跳過 50 萬筆 | 153.6 ms | 147.1 ms | 索引無法改善 |
| Q9 keyset 取同一頁 | 25.2 ms | 0.02 ms | `(price, id)` 直接定位 |
| Q10 `count(*)`（關鍵字 2 字） | 76.4 ms | 74.0 ms | 不提供總筆數的原因 |

索引大小：資料表 90 MB、trigram 索引 36 MB、`(price, id)` 索引 30 MB。

### 已知限制

- **1–2 個字的關鍵字**：trigram 以 3 個字元為單位，`'%翡翠%'` 抽不出索引條件。PostgreSQL 又高估了命中筆數，選擇沿排序索引邊掃邊過濾，命中很少時要掃過大半張表，結果比沒有索引的平行全表掃描還慢（Q5）。完全沒有命中時最差約 180 ms；同樣情況下 3 個字以上的關鍵字約 0.01 ms。中文常見兩字詞，若此情境重要，可考慮 `pg_bigm`（以 2 字元為單位）或導入專門的搜尋引擎。
- **`price` 使用 `double`**：金額以浮點數儲存，邊界值查詢可能有誤差，建議改為 `BigDecimal` / `numeric(12,2)`（會影響訂單金額，尚未修改）。
- **建立索引**：索引腳本使用一般的 `CREATE INDEX`。正式環境資料量大時應改用 `CREATE INDEX CONCURRENTLY`，避免鎖表。

重現方式：

```
docker run -d --name perf-pg -e POSTGRES_DB=trading -e POSTGRES_USER=trading -e POSTGRES_PASSWORD=trading postgres:16
docker exec -i perf-pg psql -U trading -d trading < src/main/resources/schema.sql
docker exec -i perf-pg psql -U trading -d trading < perf/seed-products.sql
docker exec -i perf-pg psql -U trading -d trading < src/main/resources/db/product-search-indexes.sql
docker exec -i perf-pg psql -U trading -d trading < perf/search-benchmark.sql
```
