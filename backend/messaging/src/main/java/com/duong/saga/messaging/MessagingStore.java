package com.duong.saga.messaging;

import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Mọi câu SQL của outbox / inbox / bộ đếm. Dùng JdbcClient để thấy rõ từng câu, nhất là phần khoá. */
@Component
public class MessagingStore {

    public static final String DUPLICATES_SUPPRESSED = "duplicates_suppressed";
    public static final String NAIVE_SEND_FAILURES = "naive_send_failures";

    /** Một dòng outbox relay vừa nhận (lease) để gửi. */
    public record OutboxRow(long id, String destination, String body, int attempts, long ageSeconds) {
    }

    private final JdbcClient jdbc;

    public MessagingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** MANDATORY: chỉ được ghi outbox BÊN TRONG transaction nghiệp vụ — đó là toàn bộ ý nghĩa của outbox. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insertOutbox(String destination, Message message, String body) {
        jdbc.sql("""
                        INSERT INTO outbox (message_id, destination, type, order_id, body, status)
                        VALUES (:messageId, :destination, :type, :orderId, :body, 'PENDING')
                        """)
                .param("messageId", message.messageId())
                .param("destination", destination)
                .param("type", message.type())
                .param("orderId", message.orderId())
                .param("body", body)
                .update();
    }

    /**
     * Nhận 1 lô message cần gửi.
     * <ul>
     *   <li>{@code FOR UPDATE SKIP LOCKED}: nhiều relay (nhiều instance) chạy song song, mỗi relay bỏ qua dòng relay khác
     *       đang khoá thay vì đứng chờ => không ai nhận trùng, không ai bị chặn.</li>
     *   <li>Đặt {@code lease_until} rồi COMMIT ngay: transaction cực ngắn, KHÔNG giữ khoá trong lúc gọi HTTP.
     *       Relay chết giữa chừng thì lease hết hạn và dòng được nhận lại.</li>
     *   <li>READ COMMITTED: chỉ khoá đúng các dòng đọc được. Ở REPEATABLE READ, câu quét theo khoảng còn khoá cả
     *       "khe" (gap) trong index và chặn INSERT outbox của các transaction nghiệp vụ đang chạy.</li>
     * </ul>
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<OutboxRow> claimBatch(int batchSize, int leaseSeconds) {
        List<Long> ids = jdbc.sql("""
                        SELECT id FROM outbox
                         WHERE status = 'PENDING'
                           AND next_attempt_at <= NOW(6)
                           AND (lease_until IS NULL OR lease_until < NOW(6))
                         ORDER BY id
                         LIMIT :limit
                           FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", batchSize)
                .query(Long.class)
                .list();
        if (ids.isEmpty()) {
            return List.of();
        }
        jdbc.sql("""
                        UPDATE outbox
                           SET lease_until = DATE_ADD(NOW(6), INTERVAL :lease SECOND), attempts = attempts + 1
                         WHERE id IN (:ids)
                        """)
                .param("lease", leaseSeconds)
                .param("ids", ids)
                .update();
        return jdbc.sql("""
                        SELECT id, destination, body, attempts, TIMESTAMPDIFF(SECOND, retry_started_at, NOW(6)) AS age_seconds
                          FROM outbox WHERE id IN (:ids) ORDER BY id
                        """)
                .param("ids", ids)
                .query((rs, n) -> new OutboxRow(rs.getLong("id"), rs.getString("destination"),
                        rs.getString("body"), rs.getInt("attempts"), rs.getLong("age_seconds")))
                .list();
    }

    public void markSent(long id) {
        jdbc.sql("UPDATE outbox SET status = 'SENT', sent_at = NOW(6), lease_until = NULL, last_error = NULL WHERE id = :id")
                .param("id", id)
                .update();
    }

    public void scheduleRetry(long id, long delayMs, String error) {
        jdbc.sql("""
                        UPDATE outbox
                           SET next_attempt_at = DATE_ADD(NOW(6), INTERVAL :micros MICROSECOND),
                               lease_until = NULL, last_error = :error
                         WHERE id = :id
                        """)
                .param("micros", delayMs * 1000)
                .param("error", truncate(error))
                .param("id", id)
                .update();
    }

    /** Dead letter: hết lượt retry hoặc message hỏng. Không tự động gửi nữa, chờ người xem (redrive). */
    public void markDead(long id, String error) {
        jdbc.sql("UPDATE outbox SET status = 'DEAD', lease_until = NULL, last_error = :error WHERE id = :id")
                .param("error", truncate(error))
                .param("id", id)
                .update();
    }

    /** Đưa 1 message DEAD về hàng đợi (sau khi đã sửa nguyên nhân). */
    public int redrive(long id) {
        return jdbc.sql("""
                        UPDATE outbox SET status = 'PENDING', attempts = 0, next_attempt_at = NOW(6), lease_until = NULL,
                               retry_started_at = NOW(6)
                         WHERE id = :id AND status = 'DEAD'
                        """)
                .param("id", id)
                .update();
    }

    /**
     * Ghi nhận message vào inbox. Trả false nếu message_id đã có => message trùng, bỏ qua.
     * Hai bản trùng đến CÙNG LÚC: bản sau chờ khoá của bản trước; bản trước commit => bản sau nhận DuplicateKey.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordInbox(Message message) {
        try {
            jdbc.sql("INSERT INTO inbox (message_id, type, order_id) VALUES (:id, :type, :orderId)")
                    .param("id", message.messageId())
                    .param("type", message.type())
                    .param("orderId", message.orderId())
                    .update();
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    public void increment(String counter) {
        jdbc.sql("INSERT INTO messaging_stats (name, value) VALUES (:name, 1) ON DUPLICATE KEY UPDATE value = value + 1")
                .param("name", counter)
                .update();
    }

    /** Redrive hàng loạt: sau sự cố kéo dài, đưa mọi message DEAD về hàng đợi. */
    public int redriveAll() {
        return jdbc.sql("""
                        UPDATE outbox SET status = 'PENDING', attempts = 0, next_attempt_at = NOW(6), lease_until = NULL,
                               retry_started_at = NOW(6)
                         WHERE status = 'DEAD'
                        """)
                .update();
    }

    public long counter(String counter) {
        return jdbc.sql("SELECT COALESCE(MAX(value), 0) FROM messaging_stats WHERE name = :name")
                .param("name", counter)
                .query(Long.class)
                .single();
    }

    public Map<String, Long> outboxCountsByStatus() {
        Map<String, Long> counts = new java.util.HashMap<>(Map.of("PENDING", 0L, "SENT", 0L, "DEAD", 0L));
        jdbc.sql("SELECT status, COUNT(*) AS c FROM outbox GROUP BY status")
                .query((rs, n) -> Map.entry(rs.getString("status"), rs.getLong("c")))
                .list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    public long inboxCount() {
        return jdbc.sql("SELECT COUNT(*) FROM inbox").query(Long.class).single();
    }

    public List<Map<String, Object>> deadLetters(int limit) {
        return jdbc.sql("""
                        SELECT id, message_id, destination, type, order_id, attempts, last_error, created_at
                          FROM outbox WHERE status = 'DEAD' ORDER BY id DESC LIMIT :limit
                        """)
                .param("limit", limit)
                .query()
                .listOfRows();
    }

    @Transactional
    public void reset() {
        jdbc.sql("DELETE FROM outbox").update();
        jdbc.sql("DELETE FROM inbox").update();
        jdbc.sql("DELETE FROM messaging_stats").update();
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 500 ? s.substring(0, 500) : s;
    }
}
