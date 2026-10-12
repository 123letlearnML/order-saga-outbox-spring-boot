package com.duong.saga.messaging;

/**
 * Xử lý 1 loại message. Chạy bên trong transaction do {@link MessageDispatcher} mở,
 * cùng transaction với việc ghi inbox và ghi outbox các message trả lời.
 */
public interface MessageHandler {

    String type();

    void handle(Message message);
}
