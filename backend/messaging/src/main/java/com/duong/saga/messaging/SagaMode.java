package com.duong.saga.messaging;

public enum SagaMode {
    /**
     * Dual-write: commit DB xong mới gửi HTTP thẳng sang service kia (retry 1 lần), không có inbox.
     * Process chết giữa "commit" và "gửi", hoặc bên nhận đang chết => message mất vĩnh viễn.
     */
    NAIVE,
    /** Transactional Outbox + relay retry tới khi thành công + Inbox chống xử lý trùng. */
    OUTBOX
}
