-- Database-per-service: mỗi service 1 database riêng, KHÔNG service nào đọc DB của service khác.
-- Chạy tự động bởi scripts/dev-mysql.ps1, hoặc tay: mysql -u root -p < scripts/mysql-setup.sql
-- Mật khẩu dưới đây CHỈ dành cho máy dev local.

CREATE DATABASE IF NOT EXISTS saga_order          CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS saga_order_test     CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS saga_inventory      CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS saga_inventory_test CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS saga_payment        CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS saga_payment_test   CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE USER IF NOT EXISTS 'devuser'@'localhost' IDENTIFIED BY 'devuser_local_123';
GRANT ALL PRIVILEGES ON saga_order.*          TO 'devuser'@'localhost';
GRANT ALL PRIVILEGES ON saga_order_test.*     TO 'devuser'@'localhost';
GRANT ALL PRIVILEGES ON saga_inventory.*      TO 'devuser'@'localhost';
GRANT ALL PRIVILEGES ON saga_inventory_test.* TO 'devuser'@'localhost';
GRANT ALL PRIVILEGES ON saga_payment.*        TO 'devuser'@'localhost';
GRANT ALL PRIVILEGES ON saga_payment_test.*   TO 'devuser'@'localhost';

GRANT PROCESS ON *.* TO 'devuser'@'localhost';
GRANT SELECT ON performance_schema.* TO 'devuser'@'localhost';
