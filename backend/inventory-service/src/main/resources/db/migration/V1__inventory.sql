CREATE TABLE products (
    sku           VARCHAR(20)  NOT NULL,
    name          VARCHAR(100) NOT NULL,
    price         BIGINT       NOT NULL, -- VND
    initial_stock INT          NOT NULL,
    available     INT          NOT NULL,
    PRIMARY KEY (sku),
    CONSTRAINT chk_products_available CHECK (available >= 0)
) ENGINE = InnoDB;

-- 1 đơn = tối đa 1 reservation (PK order_id): đặt chỗ là idempotent theo đơn.
-- RESERVED → CONFIRMED (đã thanh toán) | RESERVED → RELEASED (saga bù trừ, hoàn kho)
CREATE TABLE reservations (
    order_id     BIGINT      NOT NULL,
    status       VARCHAR(10) NOT NULL,
    total_amount BIGINT      NOT NULL,
    created_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (order_id)
) ENGINE = InnoDB;

CREATE TABLE reservation_items (
    id       BIGINT      NOT NULL AUTO_INCREMENT,
    order_id BIGINT      NOT NULL,
    sku      VARCHAR(20) NOT NULL,
    qty      INT         NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reservation_items (order_id, sku)
) ENGINE = InnoDB;

-- 3 mặt hàng khan hiếm (300 cái) để saga thật sự gặp "hết hàng"; còn lại gần như vô hạn.
INSERT INTO products (sku, name, price, initial_stock, available) VALUES
    ('SKU-01', 'Tai nghe chống ồn (hàng hiếm)', 1990000, 300, 300),
    ('SKU-02', 'Bàn phím cơ (hàng hiếm)', 1490000, 300, 300),
    ('SKU-03', 'Chuột không dây (hàng hiếm)', 690000, 300, 300),
    ('SKU-04', 'Cáp USB-C', 90000, 1000000, 1000000),
    ('SKU-05', 'Sạc nhanh 65W', 450000, 1000000, 1000000),
    ('SKU-06', 'Ốp lưng', 120000, 1000000, 1000000),
    ('SKU-07', 'Giá đỡ laptop', 350000, 1000000, 1000000),
    ('SKU-08', 'Bình giữ nhiệt', 250000, 1000000, 1000000),
    ('SKU-09', 'Sổ tay', 60000, 1000000, 1000000),
    ('SKU-10', 'Balo laptop', 790000, 1000000, 1000000);
