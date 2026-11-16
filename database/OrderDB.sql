CREATE DATABASE IF NOT EXISTS BTH4_ORDER CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE BTH4_ORDER;
CREATE TABLE IF NOT EXISTS orders (
 id INT AUTO_INCREMENT PRIMARY KEY,
 customer_name VARCHAR(255) NOT NULL,
 customer_email VARCHAR(255) NOT NULL,
 total_amount DECIMAL(10,2) NOT NULL DEFAULT 0,
 status VARCHAR(50) NOT NULL DEFAULT 'pending',
 created_at DATETIME(6) NOT NULL,
 updated_at DATETIME(6) NOT NULL,
 CONSTRAINT ck_order_status CHECK (status IN ('pending','completed','cancelled'))
);
CREATE TABLE IF NOT EXISTS order_items (
 id INT AUTO_INCREMENT PRIMARY KEY,
 order_id INT NOT NULL,
 product_id INT NOT NULL,
 product_name VARCHAR(255) NOT NULL,
 quantity INT NOT NULL,
 unit_price DECIMAL(10,2) NOT NULL,
 total_price DECIMAL(10,2) NOT NULL,
 INDEX ix_order_items_order_id(order_id),
 CONSTRAINT fk_item_order FOREIGN KEY (order_id) REFERENCES orders(id),
 CONSTRAINT ck_item_quantity CHECK (quantity > 0),
 CONSTRAINT ck_item_prices CHECK (unit_price >= 0 AND total_price >= 0)
);
