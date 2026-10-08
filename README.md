# Trading Platform

商品交易平台後端服務。接手自前一位開發者，已完成 Code Review、修正嚴重問題，並新增商品進階查詢。

- Code Review 報告：[CODE_REVIEW.md](CODE_REVIEW.md)

## 技術棧
- Java 21 / Spring Boot 4.0
- Spring Security + JWT
- Spring Data JPA / PostgreSQL
- springdoc (Swagger UI)
- 測試：JUnit 5、Mockito、Testcontainers

## 主要變更

依 commit 順序，每個 commit 對應一類問題，細節見 commit message 與 [CODE_REVIEW.md](CODE_REVIEW.md)。

| Commit | 內容 |
|--------|------|
| `8e88f9c` | 修正無法啟動：不存在的 `findByCategory`、filter 未註冊為 bean、record accessor 寫錯；停用每次啟動自動執行 `data.sql` |
| `7f71201` | 下單：條件式 UPDATE 原子扣庫存，防止超賣；扣庫存與建立訂單放在同一交易；驗證數量；總價改用 `BigDecimal`；`Long` 改用 `equals` 比較 |
| `7708435` | 商品搜尋改為參數綁定，修正 JPQL injection；刪除商品改用 `DELETE` |
| `3358fe8` | 安全性：JWT 帶角色並設定過期時間、金鑰改由設定讀取；商品寫入 API 限 ADMIN；密碼改為雜湊比對；回應不再帶出密碼；Swagger 支援帶 token |
| `2c8b1a1` | 第三部分：商品進階查詢（模糊搜尋、價格區間、排序、keyset 分頁），百萬筆資料效能驗證 |

尚未處理的中等／輕微問題（錯誤碼對應、商品輸入驗證、金額型別、schema migration 等）列在 Code Review 報告中，並註明原因。

## 第三部分：選擇 A（商品進階查詢）

選 A 而不是 B 的理由：

- **與既有問題直接相關**：原本的 `searchByName` 有 JPQL injection、`GET /api/products` 沒有分頁，百萬筆資料時會一次載入全部商品。A 正好把這兩個問題一起解決，而不是在旁邊另外加一塊功能。
- **效能可以量化驗證**：題目要求考慮百萬級資料量。索引、執行計畫與分頁方式的取捨可以用 `EXPLAIN ANALYZE` 實測，結論有數據支撐，而不只是推論。

設計重點（詳見下方「商品進階查詢」）：

- 模糊搜尋使用 `pg_trgm` GIN 索引，跳脫 `%`、`_`、`\`，避免使用者輸入被當成萬用字元
- 排序欄位採白名單，並以 `id` 作為次要排序，確保同值資料順序固定
- keyset（cursor）分頁取代 OFFSET：深分頁不會變慢，翻頁途中有資料新增也不會重複或遺漏
- 不計算總筆數：百萬筆加模糊條件的 `count(*)` 本身就要數十毫秒
- 已知限制：1–2 字的關鍵字用不到 trigram 索引，已實測並記錄

## 啟動方式

需要 Java 21 與 Docker。

1. 啟動資料庫：
   ```
   docker compose up -d
   ```
2. 啟動應用程式（首次啟動時由 Hibernate 建立資料表）。JWT 金鑰沒有預設值，必須以環境變數 `JWT_SECRET` 提供（至少 32 bytes），未設定時會啟動失敗：
   ```
   export JWT_SECRET=$(openssl rand -base64 48)
   ./mvnw spring-boot:run
   ```
   （或用 IDE 執行 `TradeApplication`，並在 Run Configuration 設定 `JWT_SECRET`。每次產生新金鑰後，舊 token 都會失效，需重新登入。）
3. 首次啟動後，另開終端機匯入初始資料與建立搜尋索引（各執行一次即可）：
   ```
   docker compose exec -T db psql -U trading -d trading < src/main/resources/data.sql
   docker compose exec -T db psql -U trading -d trading < src/main/resources/db/product-search-indexes.sql
   ```
4. Swagger UI： http://localhost:8080/swagger-ui.html
   先呼叫 `POST /api/auth/login` 取得 token，再點右上角 **Authorize** 貼上。

正式環境的 `JWT_SECRET` 應由 secret 管理服務注入，並在所有 instance 間共用同一把金鑰。

> 若 `./mvnw` 出現 permission denied，先執行 `chmod +x mvnw`，或改用 `sh mvnw`。

## 預設帳號
| 帳號 | 密碼 | 角色 |
|------|------|------|
| admin | admin123 | ADMIN |
| alice | alice123 | USER |

## 主要 API
- `POST /api/auth/login` 登入，回傳 JWT
- `GET /api/products`、`GET /api/products/{id}` 瀏覽商品
- `POST /api/products`、`PUT /api/products/{id}`、`DELETE /api/products/delete/{id}` 新增／修改／刪除商品（限 ADMIN）
- `GET /api/products/search` 商品進階查詢（見下方說明）
- `POST /api/orders`、`GET /api/orders` 下單與查詢自己的訂單

> 呼叫受保護 API 時，於 Header 帶入 `Authorization: Bearer <token>`。

## 測試

```
./mvnw test
```

測試不需要設定 `JWT_SECRET`，`src/test/resources/config/application.yml` 已提供測試專用金鑰。整合測試使用 Testcontainers 啟動 PostgreSQL 16，需要 Docker 正在執行；沒有 Docker 時這些測試會自動略過（不會失敗），其餘單元測試照常執行。

| 測試類別 | 類型 | 驗證內容 |
|----------|------|----------|
| `OrderServiceTest` | 單元測試 | 下單流程、數量驗證、總價計算、`Long` 比對 |
| `OrderServiceConcurrencyTest` | 整合測試（PostgreSQL） | 多執行緒同時下單不超賣；寫訂單失敗時庫存回滾 |
| `AuthorizationTest` | Web 層測試 | 以真實 JWT 驗證各 API 的角色權限、偽造 token 被拒 |
| `ProductSearchCriteriaTest` | 單元測試 | 搜尋參數驗證、預設值、cursor 編解碼 |
| `ProductSearchControllerTest` | Web 層測試 | 參數綁定、不合法參數回 400 |
| `ProductSearchIntegrationTest` | 整合測試（PostgreSQL） | 模糊搜尋、injection 與萬用字元跳脫、價格區間、逐頁走訪結果與一次排序完全一致 |

只跑單一類別：

```
./mvnw test -Dtest=OrderServiceConcurrencyTest
```

效能測試腳本不在 `mvn test` 中執行，重現方式見下方「效能實測」。

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
