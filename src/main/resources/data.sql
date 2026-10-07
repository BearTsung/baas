-- 密碼以 bcrypt 雜湊儲存（明文見 README 預設帳號），{bcrypt} 前綴供 DelegatingPasswordEncoder 判斷演算法
INSERT INTO users (username, password, role) VALUES ('admin', '{bcrypt}$2a$10$Ke2RvUEba4ERLJfhtNurluxAF.5EWOa7MaJZGyL30zozPy1Mt8g4W', 'ADMIN')
  ON CONFLICT DO NOTHING;
INSERT INTO users (username, password, role) VALUES ('alice', '{bcrypt}$2a$10$v4vjuGCdrluv9eM2POSHO.EefKn59HtygTKfrck3PcTCqNVcuw1Si', 'USER')
  ON CONFLICT DO NOTHING;

INSERT INTO products (name, price, stock, created_at) VALUES ('機械鍵盤', 2999.99, 10, now());
INSERT INTO products (name, price, stock, created_at) VALUES ('人體工學椅', 8800.00, 5, now());
INSERT INTO products (name, price, stock, created_at) VALUES ('4K 螢幕', 12000.00, 3, now());
