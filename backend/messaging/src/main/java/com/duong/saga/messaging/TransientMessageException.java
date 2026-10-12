package com.duong.saga.messaging;

/** Lỗi tạm thời (nhà cung cấp email chập chờn, quá tải): trả 503, bên gửi retry với backoff. */
public class TransientMessageException extends RuntimeException {

    public TransientMessageException(String message) {
        super(message);
    }
}
