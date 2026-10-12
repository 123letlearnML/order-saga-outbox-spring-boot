package com.duong.saga.messaging;

/**
 * Message hỏng (thiếu field, sai kiểu, type lạ): gửi lại bao nhiêu lần cũng hỏng => trả 400,
 * bên gửi chuyển thẳng vào DEAD thay vì retry vô ích (poison message).
 */
public class NonRetryableMessageException extends RuntimeException {

    public NonRetryableMessageException(String message) {
        super(message);
    }
}
