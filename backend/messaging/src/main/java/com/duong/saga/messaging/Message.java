package com.duong.saga.messaging;

import java.util.Map;
import java.util.UUID;

/**
 * Phong bì message giữa các service.
 *
 * @param messageId UUID duy nhất — khoá chống trùng ở inbox bên nhận. Gửi lại thì GIỮ NGUYÊN id.
 * @param orderId   saga id: mọi message của 1 đơn hàng mang cùng orderId (để truy vết)
 */
public record Message(String messageId, String type, long orderId, String source, Map<String, Object> payload) {

    public static Message create(String type, long orderId, String source, Map<String, Object> payload) {
        return new Message(UUID.randomUUID().toString(), type, orderId, source, payload);
    }

    public long getLong(String key) {
        Object value = require(key);
        if (value instanceof Number n) {
            return n.longValue();
        }
        throw new NonRetryableMessageException("Field '" + key + "' is not a number");
    }

    public String getString(String key) {
        return String.valueOf(require(key));
    }

    public Object get(String key) {
        return require(key);
    }

    private Object require(String key) {
        Object value = payload == null ? null : payload.get(key);
        if (value == null) {
            throw new NonRetryableMessageException("Missing field '" + key + "' in " + type);
        }
        return value;
    }
}
