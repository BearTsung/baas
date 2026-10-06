-- 產生 100 萬筆測試商品（僅供效能測試，請勿在正式資料庫執行）
-- 名稱 = 形容詞 + 品項 + 型號 + 唯一 SKU；每 10,000 筆有 1 筆「翡翠茶杯」，用來測試低命中率的關鍵字
INSERT INTO products (name, price, stock, created_at)
SELECT CASE WHEN g % 10000 = 0 THEN '翡翠茶杯'
            ELSE (ARRAY['機械','無線','人體工學','電競','藍牙','靜音','輕薄','專業'])[1 + (g % 8)]
              || (ARRAY['鍵盤','滑鼠','螢幕','耳機','椅子','筆電','喇叭','麥克風'])[1 + ((g / 8) % 8)]
       END
       || ' ' || (ARRAY['Pro','Max','Mini','Lite','Ultra'])[1 + ((g / 64) % 5)]
       || ' SKU-' || lpad(g::text, 7, '0'),
       round((random() * 50000)::numeric, 2)::float8,
       (random() * 100)::int,
       now() - random() * interval '365 days'
FROM generate_series(1, 1000000) AS g;

ANALYZE products;
