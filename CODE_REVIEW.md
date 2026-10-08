# Code Review 報告

審查對象：接手時的原始程式碼（baseline commit `5b2dd73 init`）。
每一項註明位置、後果、嚴重程度與目前處理狀態；修正對應的 commit 列在「狀態」欄，可用 `git show <commit>` 對照。

嚴重程度定義：

- 🔴 嚴重：資料錯誤、資安漏洞、金額錯誤或服務無法啟動，上線前必須修
- 🟡 中等：特定情境下行為錯誤、維運困難，或 API 契約不正確
- 🟢 輕微：可讀性、一致性、日誌等，不影響正確性

## 總覽

| # | 問題 | 嚴重程度 | 狀態 |
|---|------|:---:|------|
| 1 | 商品搜尋 JPQL injection | 🔴 | 已修正 `7708435`、`2c8b1a1` |
| 2 | 角色權限沒有實作，任何登入者都能新增／修改／刪除商品 | 🔴 | 已修正 `3358fe8` |
| 3 | 查詢訂單的回應帶出使用者密碼 | 🔴 | 已修正 `3358fe8` |
| 4 | 密碼明文儲存、明文比對 | 🔴 | 已修正 `3358fe8`；`data.sql` 改為雜湊 `1720cd0` |
| 5 | 併發下單會超賣 | 🔴 | 已修正 `7f71201` |
| 6 | 扣庫存與建立訂單不在同一個交易 | 🔴 | 已修正 `7f71201` |
| 7 | 下單沒有驗證：負數數量會增加庫存 | 🔴 | 已修正 `7f71201` |
| 8 | 訂單總價以 `int` 截斷小數 | 🔴 | 已修正 `7f71201` |
| 9 | JWT 永不過期 | 🔴 | 已修正 `3358fe8` |
| 10 | JWT 金鑰寫死在原始碼 | 🔴 | 已修正 `3358fe8`；移除預設金鑰 `1720cd0` |
| 11 | 服務無法啟動（三處） | 🔴 | 已修正 `8e88f9c` |
| 12 | 用 `==` 比較 `Long`，id 超過 127 查不到訂單 | 🟡 | 已修正 `7f71201` |
| 13 | 訂單編號與計數器在併發下錯誤 | 🟡 | 已修正 `7f71201` |
| 14 | 用 GET 刪除商品 | 🟡 | 已修正 `7708435` |
| 15 | 登入時吞掉例外 | 🟡 | 已修正 `3358fe8` |
| 16 | `data.sql` 的 `ON CONFLICT` 不會生效，使用者名稱沒有唯一限制 | 🟡 | 部分修正 `8e88f9c` |
| 17 | 錯誤一律回 500 或 200 | 🟡 | 部分修正 `2c8b1a1` |
| 18 | 商品資料沒有輸入驗證 | 🟡 | 未修正 |
| 19 | 金額使用 `double` | 🟡 | 未修正（已記錄於 README） |
| 20 | 直接回傳 JPA Entity、`getUserOrders` 多餘查詢 | 🟡 | 部分修正 `2c8b1a1` |
| 21 | 商品列表沒有分頁 | 🟡 | 以新的搜尋 API 取代 `2c8b1a1` |
| 22 | 以 `ddl-auto: update` 管理正式環境 schema | 🟡 | 未修正 |
| 23 | 與實際行為不符的註解 | 🟢 | 已隨各項修正 |
| 24 | `user.getRole() == "ADMIN"` | 🟢 | 已修正 `3358fe8` |
| 25 | 以 `System.out` 記錄日誌、正式環境開啟 `show-sql` | 🟢 | 未修正 |
| 26 | 其他小問題 | 🟢 | 未修正 |

---

## 🔴 嚴重

### 1. 商品搜尋 JPQL injection

- **位置**：`ProductService.searchByName`
  ```java
  String jpql = "SELECT p FROM Product p WHERE p.name LIKE '%" + keyword + "%'";
  ```
- **後果**：使用者輸入直接串進查詢字串。`keyword = "' OR '1'='1"` 可以撈出全部商品，也可以構造其他條件讀取不該看到的資料。上方註解卻寫「已改用參數化查詢」，容易讓人誤以為安全。
- **修正**：改用 Spring Data 的 `findByNameContainingIgnoreCase`，由框架做參數綁定（`7708435`）；第三部分重寫搜尋時改為 JPA Specification，並跳脫 `%`、`_`、`\`，避免使用者輸入被當成萬用字元（`2c8b1a1`）。
- **佐證**：`ProductSearchIntegrationTest` 的 `keyword_injectionPayload_isTreatedAsLiteral`、`keyword_percent_isEscaped`、`keyword_underscore_isEscaped`。

### 2. 角色權限沒有實作

- **位置**：`SecurityConfig`、`JwtAuthenticationFilter`、`JwtUtil`、`ProductController`
- **後果**：`SecurityConfig` 的註解寫「新增／修改／刪除僅限 ADMIN」，實際只有 `anyRequest().authenticated()`。JWT 沒有帶角色，`JwtAuthenticationFilter` 給的 authorities 也是空的，所以 alice（USER）可以新增、修改、刪除商品。
- **修正**：簽發 token 時加入 `role` claim，filter 轉成 `ROLE_xxx`；開啟 `@EnableMethodSecurity`，商品寫入 API 加上 `@PreAuthorize("hasRole('ADMIN')")`。
- **佐證**：`AuthorizationTest` 用真實 JWT 走完 filter → security → controller，涵蓋未登入、偽造簽章、USER 不能寫入、ADMIN 可寫入、token 沒有 role 等情境。

### 3. 查詢訂單的回應帶出使用者密碼

- **位置**：`OrderController.myOrders` 回傳 `List<Order>`，`Order.user` 是完整的 `User` entity
- **後果**：`GET /api/orders` 的 JSON 含有 `user.password`。配合第 4 項的明文儲存，等於直接把密碼回傳給前端。
- **修正**：`User.password` 加上 `@JsonIgnore`。更完整的做法是回傳 DTO（見第 20 項）。

### 4. 密碼明文儲存、明文比對

- **位置**：`AuthService.login` 使用 `user.getPassword().equals(request.getPassword())`；`data.sql` 寫入明文密碼。註解卻寫「以 BCrypt 雜湊比對」。
- **後果**：資料庫外洩即等於所有帳號外洩。
- **修正**：新增 `PasswordEncoder`（`DelegatingPasswordEncoder`，預設 bcrypt），登入改用 `passwordEncoder.matches`。
- **後續補正**：第一次修正時漏改 `data.sql`，裡面仍是明文。`DelegatingPasswordEncoder` 遇到沒有 `{bcrypt}` 前綴的密碼會拋出例外，在全新環境匯入 `data.sql` 後無法登入（本機資料庫先前已手動改成雜湊，所以沒發現）。已改為 `{bcrypt}$2a$10$...` 雜湊值。
- **佐證**：以全新的 PostgreSQL 容器照 README 步驟啟動、匯入 `data.sql`，admin、alice 皆可登入，錯誤密碼被拒；alice 新增商品回 403，admin 回 200。

### 5. 併發下單會超賣

- **位置**：`OrderService.placeOrder` / `deductStock`
- **後果**：
  - 「讀庫存 → 判斷 → 寫回 `stock - quantity`」不是原子操作，兩個請求同時讀到 stock = 1 時都會成功。
  - `synchronized` 只在單一 JVM 內有效，多台 instance 就沒用；而且它只包住寫回，判斷庫存的那一段不在鎖內。
  - `deductStock` 是 `placeOrder` 透過 `this` 呼叫的，Spring proxy 攔截不到，`@Transactional` 沒有作用。
  - 註解寫「已使用樂觀鎖」，但 entity 沒有 `@Version`。
- **修正**：改成條件式 UPDATE，判斷和扣減在同一個 SQL 中由資料庫保證原子性：
  ```sql
  update Product p set p.stock = p.stock - :qty where p.id = :id and p.stock >= :qty
  ```
  影響筆數為 0 時視為庫存不足。
- **佐證**：`OrderServiceConcurrencyTest`（Testcontainers + 真實 PostgreSQL）以多執行緒同時下單，確認成功筆數剛好等於庫存、庫存歸零且不為負數；另測一次下多件的情境。

### 6. 扣庫存與建立訂單不在同一個交易

- **位置**：`OrderService.placeOrder` 沒有 `@Transactional`
- **後果**：庫存扣了，但 `orderRepository.save` 失敗時，庫存不會回補，商品憑空消失。
- **修正**：`placeOrder` 加上 `@Transactional`，扣庫存與寫訂單一起提交或一起回滾。
- **佐證**：`OrderServiceConcurrencyTest.saveOrderFails_stockIsRolledBack` 讓寫入訂單失敗，確認庫存回到原值。

### 7. 下單沒有驗證：負數數量會增加庫存

- **位置**：`OrderService.validateOrder` 永遠回傳 `true`；`product` 為 `null` 時沒有檢查
- **後果**：
  - `quantity = -5` 時，`stock < -5` 不成立，`stock - (-5)` 讓庫存增加 5，訂單總價為負數。
  - `quantity = null` 或商品不存在時拋出 `NullPointerException`。
- **修正**：數量必須為正整數；商品不存在、使用者不存在時拒絕下單。
- **佐證**：`OrderServiceTest.placeOrder_invalidQuantity`（null、0、負數）、`placeOrder_userNotFound`。

### 8. 訂單總價以 `int` 截斷小數

- **位置**：`order.setTotalPrice((int) product.getPrice() * request.getQuantity())`
- **後果**：轉型先作用在價格上，2999.99 元的鍵盤買 3 個變成 8997 元，每筆訂單都少收錢。
- **修正**：改以 `BigDecimal` 計算。
- **佐證**：`OrderServiceTest.placeOrder_totalPriceKeepsDecimals`。

### 9. JWT 永不過期

- **位置**：`JwtUtil.generateToken` 沒有設定 `expiration`；`application.yml` 的 `jwt.expiration-ms` 沒有被讀取。註解寫「1 小時自動過期」。
- **後果**：token 外洩後永久有效，也無法讓角色變更或停用帳號生效。
- **修正**：讀取 `jwt.expiration-ms` 設定 `exp`，jjwt 驗證時會檢查過期。

### 10. JWT 金鑰寫死在原始碼

- **位置**：`JwtUtil.SECRET` 為常數；`application.yml` 的 `jwt.secret` 沒有被使用
- **後果**：任何看得到原始碼的人都能簽出 ADMIN token。
- **修正**：改從 `jwt.secret` 讀取。
- **後續補正**：第一次修正後，`application.yml` 仍保留預設值 `${JWT_SECRET:change-me-in-prod-please-rotate-this-secret}`，部署時忘了設定 `JWT_SECRET` 就會用這把寫在 repo 裡的金鑰。已移除預設值，改為 `${JWT_SECRET}`，未設定時啟動失敗（fail fast）。測試改由 `src/test/resources/config/application.yml` 提供測試專用金鑰，不影響 main 設定。
- **佐證**：未設定 `JWT_SECRET` 啟動時出現 `Could not resolve placeholder 'JWT_SECRET'` 並結束；設定後正常啟動，全部測試通過。

### 11. 服務無法啟動

- **位置與後果**：
  - `ProductRepository.findByCategory`：`Product` 沒有 `category` 欄位，Spring Data 建立 repository 時失敗。
  - `JwtAuthenticationFilter` 沒有 `@Component`，`SecurityConfig` 無法注入。
  - `ProductService` 呼叫 `request.getName()`，但 `ProductRequest` 是 record，accessor 是 `name()`，編譯失敗。
- **修正**：`8e88f9c`。`findByCategory` 先註解保留，待確認是否有分類需求。

---

## 🟡 中等

### 12. 用 `==` 比較 `Long`

- **位置**：`OrderService.getUserOrders` 的 `o.getUser().getId() == user.getId()`
- **後果**：`Long` 只快取 -128～127，id 超過 127 的使用者比對結果為 `false`，查不到自己的訂單。
- **修正**：改用 `equals`。
- **佐證**：`OrderServiceTest.getUserOrders_comparesIdsByValue` 使用 id = 1000。

### 13. 訂單編號與計數器在併發下錯誤

- **位置**：`OrderService` 的 `SimpleDateFormat` 欄位與 `int placedOrderCount`
- **後果**：`SimpleDateFormat` 不是 thread-safe，共用會產生錯亂的字串；以毫秒時間當單號，同一毫秒的訂單會重複；單號也沒有存進資料庫，只印在 log。`placedOrderCount++` 不是原子操作，會少算。
- **修正**：移除時間單號，log 改用資料庫產生的訂單 id；計數改用 `AtomicInteger`。

### 14. 用 GET 刪除商品

- **位置**：`ProductController.delete` 使用 `@GetMapping("/delete/{id}")`
- **後果**：GET 應該是安全、可重複的操作。瀏覽器預先載入、爬蟲、快取都可能觸發刪除。
- **修正**：改為 `@DeleteMapping`。路徑 `/delete/{id}` 先保留，改成 `DELETE /api/products/{id}` 較符合 REST 慣例，但會再改動一次 API，留待與前端一起調整。

### 15. 登入時吞掉例外

- **位置**：`AuthService.login` 的 `catch (Exception e) { // ignore }`
- **後果**：資料庫斷線等系統錯誤被當成「帳密錯誤」，使用者和維運人員都看不到真正原因。
- **修正**：記錄錯誤並拋出 `AuthenticationServiceException`。

### 16. `data.sql` 的 `ON CONFLICT` 不會生效

- **位置**：`data.sql`、`application.yml` 的 `spring.sql.init.mode: always`
- **後果**：`users`、`products` 都沒有 unique 限制，`ON CONFLICT DO NOTHING` 永遠不會觸發，每次啟動都重複插入。使用者名稱重複後，`findByUsername` 回傳多筆而拋出例外，該帳號無法登入。
- **修正**：`sql.init.mode` 改為 `never`，初始資料改為手動匯入一次。
- **後續補正**：第三部分將 `Product.createdAt` 設為 `nullable = false` 後，Hibernate 建出的欄位是 NOT NULL 但沒有預設值，`data.sql` 的商品資料在全新資料庫上寫入失敗。已在 `data.sql` 明確給 `created_at = now()`。
- **尚未完成**：`users.username` 應加上 unique 限制。

### 17. 錯誤一律回 500 或 200

- **位置**：
  - `AuthController.login` 登入失敗回 200 + 字串「登入失敗」
  - `OrderService` 的業務錯誤都是 `RuntimeException` → 500
  - `ProductService.updateProduct` 使用 `findById(id).get()` → 500
  - `ProductService.getProduct` 找不到時回傳 `null` → 200 空內容
  - 刪除已有訂單的商品違反外鍵 → 500
- **後果**：前端無法區分「參數錯誤」「找不到」「庫存不足」「系統錯誤」，監控也會把使用者錯誤算成伺服器錯誤。
- **處理**：新增 `GlobalExceptionHandler` 與 `BadRequestException`，搜尋 API 的參數錯誤回 400 ProblemDetail。其餘 API 尚未套用，預計對應為：登入失敗 401、找不到 404、庫存不足 409、參數錯誤 400。

### 18. 商品資料沒有輸入驗證

- **位置**：`ProductRequest`、`ProductController.create` / `update`
- **後果**：可以建立負價格、負庫存、沒有名稱的商品；`price` 是 `double` 基本型別，請求漏帶時會變成 0。`pom.xml` 引入了 validation starter，但沒有任何地方使用 `@Valid`。
- **處理**：未修正。建議 `ProductRequest` 加上 `@NotBlank`、`@PositiveOrZero` 等限制並在 controller 加 `@Valid`。

### 19. 金額使用 `double`

- **位置**：`Product.price`、`Order.totalPrice`、`schema.sql` 的 `DOUBLE PRECISION`
- **後果**：浮點數無法精確表示 0.1 等十進位小數，累加與比較會有誤差。
- **處理**：未修正。訂單總價的計算已改用 `BigDecimal`（第 8 項），但儲存型別要改成 `numeric(12,2)` 會牽動 schema、DTO 與既有資料，應搭配 migration 工具一起做（見第 22 項）。

### 20. 直接回傳 JPA Entity

- **位置**：所有 controller 回傳 `Product`、`Order`；`Order`、`Product`、`User` 都使用 `@Data`
- **後果**：
  - 回應格式綁死在資料表結構，新增欄位就會外露（第 3 項就是這樣發生的）。
  - `@Data` 產生的 `equals`/`hashCode` 包含可變的 `id`，放進 `Set` 後再儲存會出錯；`toString` 包含 `password`，容易被印進 log。
  - `getUserOrders` 已經用 `findByUserId` 過濾，迴圈又比對一次 user id；`o.getProduct().getName()` 這類呼叫是為了觸發載入，每筆訂單都會產生額外查詢（N+1）。
- **處理**：新的搜尋 API 回傳 `ProductResponse` DTO。其餘 API 尚未改。

### 21. 商品列表沒有分頁

- **位置**：`ProductService.listProducts` 使用 `findAll()`
- **後果**：百萬筆商品時一次載入全部資料，記憶體與回應時間都會出問題。
- **處理**：第三部分新增 `GET /api/products/search`，支援 keyset 分頁，不帶參數即為依建立時間排序的第一頁。`GET /api/products` 為了相容先保留，建議前端改用搜尋 API 後移除。

### 22. 以 `ddl-auto: update` 管理 schema

- **位置**：`application.yml`
- **後果**：`update` 只會新增，不會修改或刪除欄位與限制，也沒有版本紀錄；`schema.sql` 與 entity 定義不一致時，實際 schema 取決於誰先執行。
- **處理**：未修正。建議導入 Flyway，把 `schema.sql`、`db/product-search-indexes.sql` 與第 16、19 項的變更整理成 migration。

---

## 🟢 輕微

### 23. 與實際行為不符的註解

原始程式有多處註解描述的是「應該要有」的行為，實際並沒有做到，接手者很容易被誤導：

| 位置 | 註解 | 實際 |
|------|------|------|
| `ProductService.searchByName` | 已改用參數化查詢 | 字串串接（第 1 項） |
| `SecurityConfig` | 新增／修改／刪除僅限 ADMIN | 沒有角色檢查（第 2 項） |
| `AuthService.login` | 以 BCrypt 雜湊比對 | 明文比對（第 4 項） |
| `OrderService.placeOrder` | 已使用樂觀鎖 | 沒有 `@Version`（第 5 項） |
| `JwtUtil.generateToken` | 1 小時自動過期 | 沒有設定過期（第 9 項） |
| `OrderService.validateOrder` | 驗證下單數量與商品是否合法 | 永遠回傳 `true`（第 7 項） |

各項修正時已一併讓註解與程式一致。

### 24. `user.getRole() == "ADMIN"`

字串用 `==` 比較的是參考，從資料庫讀出的字串永遠不會相等。目前只影響一行 log，改為 `"ADMIN".equals(...)`。

### 25. 以 `System.out` 記錄日誌

`AuthService`、`OrderService` 使用 `System.out.println`，沒有等級、時間與追蹤資訊；`show-sql: true` 在正式環境會產生大量 log。建議改用 SLF4J，並以 profile 區分開發與正式設定。

### 26. 其他

- `JwtUtil` 每個請求解析同一個 token 三次（`validate`、`getUsername`、`getRole`），可合併成一次。欄位 `expirationMinute` 實際存的是毫秒。
- 未登入時回 403，應為 401（需設定 `AuthenticationEntryPoint`）。
- 商品不存在時下單回「庫存不足」，訊息不精確。
- 資料庫密碼寫在 `application.yml` 與 `docker-compose.yml`，正式環境應改用環境變數或 secret。
- `mvnw` 在 git 中沒有執行權限（mode `100644`），clone 後 `./mvnw` 會出現 permission denied，需執行 `git update-index --chmod=+x mvnw`。

---

## 看起來怪，但決定不改

| 寫法 | 為什麼保留 |
|------|------|
| `SecurityConfig` 關閉 CSRF | token 放在 `Authorization` header，不是 cookie。瀏覽器不會自動帶上，跨站請求偽造無從發生；開啟 CSRF 反而要求每個 API 呼叫額外處理 CSRF token。若日後改成 cookie 存 token，需重新開啟。 |
| 角色從 JWT 讀取，不在每個請求查資料庫 | 每個請求省一次資料庫查詢。代價是角色變更或停用帳號要等 token 過期（1 小時）才生效。目前規模可接受；若需要立即失效，再加 token 黑名單或縮短效期搭配 refresh token。 |
| 扣庫存用條件式 UPDATE，不用樂觀鎖或悲觀鎖 | 樂觀鎖在搶購時大量失敗，需要重試邏輯；悲觀鎖（`SELECT ... FOR UPDATE`）多一次往返，持鎖時間也較長。條件式 UPDATE 一個 SQL 完成判斷與扣減，由資料庫的列鎖保證正確，也適用於多台 instance。 |
| `placedOrderCount` 保留為單機計數 | 只用於 log，不是業務資料，各 instance 各自計數即可。改成 `AtomicInteger` 修正併發少算的問題，不值得為此寫進資料庫。 |
| `Order.totalPrice` 仍以 `double` 儲存 | 計算已改用 `BigDecimal`，避免截斷與累積誤差。改儲存型別牽動 schema 與 API，應與第 19 項一起透過 migration 處理，不單獨修改。 |
| `spring.sql.init.mode: never` | 初始資料不該每次啟動都執行。改為部署時手動匯入一次，避免重複資料；長期應改成 Flyway migration。 |
| 搜尋 API 不回傳總筆數 | 百萬筆資料加上模糊條件時，`count(*)` 本身就要數十毫秒，且使用者很少需要精確總數。詳見 README。 |



ＡＩ輔助：
查詢需要修改的問題
第三部分的實作（AI想採用Flyway的方式記錄git變動，依照過往使用經驗，除非開發人數過多導致資安問題，否則這是一個降低初步開發速度的功能）
寫測試
依照變動提供需要繳交的報告