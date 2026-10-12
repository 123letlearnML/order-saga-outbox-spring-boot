package com.duong.saga.messaging;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param service tên service hiện tại (dùng làm "source" của message)
 * @param peers   tên service → base URL, ví dụ inventory → http://localhost:8092
 */
@ConfigurationProperties("saga")
public record SagaProperties(@DefaultValue("OUTBOX") SagaMode mode,
                             String service,
                             Map<String, String> peers,
                             @DefaultValue Relay relay,
                             @DefaultValue Chaos chaos) {

    /**
     * @param maxAttempts     quá số lần này => DEAD (dead letter), chờ người xử lý
     * @param maxRetrySeconds lỗi tạm thời: cứ retry tới khi message "già" hơn mức này rồi mới DEAD.
     *                        Phải DÀI HƠN sự cố lâu nhất có thể xảy ra (xem KNOWLEDGE.md: bỏ cuộc sau ~90s
     *                        đã làm 473 message rơi vào DEAD trong chaos test)
     * @param leaseSeconds    relay giữ dòng outbox trong bao lâu; chết thì hết hạn và relay khác lấy lại
     */
    public record Relay(@DefaultValue("true") boolean enabled,
                        @DefaultValue("50") int batchSize,
                        @DefaultValue("50") long pollIntervalMs,
                        @DefaultValue("1000") int maxAttempts,
                        @DefaultValue("1800") long maxRetrySeconds,
                        @DefaultValue("200") long baseBackoffMs,
                        @DefaultValue("5000") long maxBackoffMs,
                        @DefaultValue("10") int leaseSeconds,
                        @DefaultValue("16") int sendConcurrency) {
    }

    /** @param duplicateRate xác suất 1 message bị gửi 2 lần — mô phỏng mạng/broker at-least-once */
    public record Chaos(@DefaultValue("0") double duplicateRate) {
    }
}
