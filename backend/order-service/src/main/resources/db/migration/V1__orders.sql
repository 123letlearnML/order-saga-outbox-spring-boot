-- Trạng thái đơn (state machine của saga):
--   PENDING ──StockReserved──► STOCK_RESERVED ──PaymentCompleted──► CONFIRMED
--      │                             │
--      └──StockRejected──► REJECTED  └──PaymentFailed──► CANCELLED (+ hoàn kho)
CREATE TABLE orders (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    idempotency_key VARCHAR(64)  NOT NULL,
    customer_id     BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    total_amount    BIGINT       NULL, -- inventory tính và trả về (giá thuộc về inventory)
    failure_reason  VARCHAR(100) NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_idempotency_key (idempotency_key),
    KEY idx_orders_status (status)
) ENGINE = InnoDB;

CREATE TABLE order_items (
    id       BIGINT      NOT NULL AUTO_INCREMENT,
    order_id BIGINT      NOT NULL,
    sku      VARCHAR(20) NOT NULL,
    qty      INT         NOT NULL,
    PRIMARY KEY (id),
    KEY idx_order_items_order (order_id)
) ENGINE = InnoDB;

-- Email đã gửi qua nhà cung cấp bên ngoài. Cố ý KHÔNG có unique: nhà cung cấp email không idempotent,
-- gọi 2 lần là khách nhận 2 email. Chỉ có Inbox mới chặn được trùng ở đây.
CREATE TABLE sent_emails (
    id       BIGINT       NOT NULL AUTO_INCREMENT,
    order_id BIGINT       NOT NULL,
    subject  VARCHAR(200) NOT NULL,
    sent_at  DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_sent_emails_order (order_id)
) ENGINE = InnoDB;
