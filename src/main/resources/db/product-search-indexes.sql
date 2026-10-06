-- 商品進階查詢（模糊搜尋 + 價格區間 + 排序 + keyset 分頁）所需的索引
-- pg_trgm 擴充與 GIN 運算式索引無法由 Hibernate ddl-auto 建立，需在資料表建立後手動執行一次；
-- 全部使用 IF NOT EXISTS，重複執行不會出錯。整合測試以 @Sql 套用同一份腳本。
-- 正式環境資料量大時，應改用 CREATE INDEX CONCURRENTLY 並在交易外執行，避免長時間鎖表

CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- keyset 分頁以 (created_at, id) 定位，created_at 不可為 NULL
UPDATE products SET created_at = now() WHERE created_at IS NULL;
ALTER TABLE products ALTER COLUMN created_at SET DEFAULT now();
ALTER TABLE products ALTER COLUMN created_at SET NOT NULL;

-- 模糊搜尋：運算式需與查詢的 lower(name) LIKE ? 完全一致才會用到索引
CREATE INDEX IF NOT EXISTS idx_products_name_trgm ON products USING gin (lower(name) gin_trgm_ops);

-- 價格區間與排序；id 作為同值時的次要排序，讓 keyset 分頁能直接從索引定位
CREATE INDEX IF NOT EXISTS idx_products_price_id ON products (price, id);
CREATE INDEX IF NOT EXISTS idx_products_created_at_id ON products (created_at, id);
