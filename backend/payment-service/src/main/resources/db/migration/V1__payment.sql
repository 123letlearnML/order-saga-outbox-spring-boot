CREATE TABLE wallets (
    customer_id     BIGINT NOT NULL,
    balance         BIGINT NOT NULL,
    initial_balance BIGINT NOT NULL,
    PRIMARY KEY (customer_id),
    CONSTRAINT chk_wallets_balance CHECK (balance >= 0)
) ENGINE = InnoDB;

-- 1 đơn = tối đa 1 lần thanh toán (PK order_id): chốt chặn cuối cùng chống trừ tiền 2 lần.
CREATE TABLE payments (
    order_id    BIGINT      NOT NULL,
    customer_id BIGINT      NOT NULL,
    amount      BIGINT      NOT NULL,
    status      VARCHAR(12) NOT NULL, -- CHARGED | DECLINED | INSUFFICIENT
    created_at  DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (order_id)
) ENGINE = InnoDB;

-- 1.000 khách. Cứ 10 khách có 1 người ví mỏng (300.000 ₫) => thường thiếu tiền => saga phải hoàn kho.
SET SESSION cte_max_recursion_depth = 2000;
INSERT INTO wallets (customer_id, balance, initial_balance)
WITH RECURSIVE seq (n) AS (SELECT 1 UNION ALL SELECT n + 1 FROM seq WHERE n < 1000)
SELECT n,
       CASE WHEN n % 10 = 0 THEN 300000 ELSE 50000000 END,
       CASE WHEN n % 10 = 0 THEN 300000 ELSE 50000000 END
FROM seq;
