-- 對應 ProductSpecifications 產生的 SQL（Hibernate 以 fetch first ? rows only 表示 LIMIT）
-- 每頁 20 筆，多取 1 筆判斷 hasNext，因此 LIMIT 21
\pset pager off

\echo '== Q1 預設首頁：依建立時間新到舊'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM products ORDER BY created_at DESC, id DESC LIMIT 21;

\echo '== Q2 關鍵字（4 字，命中約 1.6%）+ 預設排序'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM products WHERE lower(name) LIKE '%靜音鍵盤%' ESCAPE '\'
ORDER BY created_at DESC, id DESC LIMIT 21;

\echo '== Q3 關鍵字（SKU，命中 1 筆）'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM products WHERE lower(name) LIKE '%sku-0123457%' ESCAPE '\'
ORDER BY created_at DESC, id DESC LIMIT 21;

\echo '== Q4 關鍵字（4 字，命中 100 筆）'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM products WHERE lower(name) LIKE '%翡翠茶杯%' ESCAPE '\'
ORDER BY created_at DESC, id DESC LIMIT 21;

\echo '== Q5 關鍵字（2 字，命中 100 筆）：trigram 抽不出索引條件'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM products WHERE lower(name) LIKE '%翡翠%' ESCAPE '\'
ORDER BY created_at DESC, id DESC LIMIT 21;

\echo '== Q6 價格區間 + 依價格排序'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM products WHERE price >= 1000 AND price <= 1100
ORDER BY price, id LIMIT 21;

\echo '== Q7 關鍵字（2 字，命中 12.5%）+ 價格區間 + 依價格排序'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM products WHERE lower(name) LIKE '%鍵盤%' ESCAPE '\' AND price >= 1000 AND price <= 2000
ORDER BY price, id LIMIT 21;

-- 第 25,001 頁（跳過 50 萬筆）的位置，供 Q8 / Q9 比較
SELECT price AS p, id AS i FROM products ORDER BY price, id OFFSET 499999 LIMIT 1 \gset

\echo '== Q8 OFFSET 深分頁：跳過 50 萬筆'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM products ORDER BY price, id OFFSET 500000 LIMIT 21;

\echo '== Q9 keyset 取同一頁（與 ProductSpecifications.after 相同的條件寫法）'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT * FROM products WHERE price >= :p AND (price > :p OR id > :i)
ORDER BY price, id LIMIT 21;

\echo '== Q10 計算總筆數（Page 會多跑這一句）'
EXPLAIN (ANALYZE, BUFFERS, COSTS OFF)
SELECT count(*) FROM products WHERE lower(name) LIKE '%鍵盤%' ESCAPE '\';
