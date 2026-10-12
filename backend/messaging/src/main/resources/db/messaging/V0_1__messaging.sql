-- Bảng dùng chung cho MỌI service (mỗi service có bản riêng trong database của mình).

-- Transactional Outbox: message cần gửi đi được INSERT cùng transaction với thay đổi nghiệp vụ.
-- Commit thành công => chắc chắn message nằm đây; relay sẽ gửi dần, gửi lại tới khi thành công.
CREATE TABLE outbox (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    message_id      CHAR(36)     NOT NULL,
    destination     VARCHAR(30)  NOT NULL, -- tên service nhận
    type            VARCHAR(50)  NOT NULL,
    order_id        BIGINT       NOT NULL,
    body            TEXT         NOT NULL, -- JSON của cả message
    status          VARCHAR(10)  NOT NULL, -- PENDING | SENT | DEAD
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    -- "Hợp đồng thuê": relay đã nhận dòng này tới thời điểm này. Relay chết giữa chừng => hết hạn => relay khác lấy lại.
    lease_until     DATETIME(6)  NULL,
    last_error      VARCHAR(500) NULL,
    created_at      DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    sent_at         DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_message_id (message_id),
    KEY idx_outbox_pending (status, next_attempt_at)
) ENGINE = InnoDB;

-- Inbox: message_id đã xử lý. Nhận trùng (at-least-once) => INSERT trùng khoá => bỏ qua.
CREATE TABLE inbox (
    message_id  CHAR(36)    NOT NULL,
    type        VARCHAR(50) NOT NULL,
    order_id    BIGINT      NOT NULL,
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (message_id)
) ENGINE = InnoDB;

-- Bộ đếm sống sót qua kill -9 (bộ đếm trong RAM thì mất khi process chết).
CREATE TABLE messaging_stats (
    name  VARCHAR(50) NOT NULL,
    value BIGINT      NOT NULL,
    PRIMARY KEY (name)
) ENGINE = InnoDB;
