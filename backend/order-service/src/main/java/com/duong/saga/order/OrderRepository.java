package com.duong.saga.order;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    public record Order(long id, String idempotencyKey, long customerId, String status, Long totalAmount,
                        String failureReason) {
    }

    public record Item(String sku, int qty) {
    }

    private final JdbcClient jdbc;

    public OrderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insertOrder(String idempotencyKey, long customerId, List<Item> items) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO orders (idempotency_key, customer_id, status) VALUES (:key, :customer, 'PENDING')")
                .param("key", idempotencyKey)
                .param("customer", customerId)
                .update(keyHolder);
        long orderId = keyHolder.getKey().longValue();
        for (Item item : items) {
            jdbc.sql("INSERT INTO order_items (order_id, sku, qty) VALUES (:id, :sku, :qty)")
                    .param("id", orderId).param("sku", item.sku()).param("qty", item.qty()).update();
        }
        return orderId;
    }

    public Optional<Order> findByIdempotencyKey(String key) {
        return jdbc.sql("SELECT * FROM orders WHERE idempotency_key = :key").param("key", key)
                .query(OrderRepository::map).optional();
    }

    public Optional<Order> findById(long id) {
        return jdbc.sql("SELECT * FROM orders WHERE id = :id").param("id", id)
                .query(OrderRepository::map).optional();
    }

    /** Khoá đơn để chuyển trạng thái: 2 message cùng đơn đến song song thì xếp hàng, không ai ghi đè ai. */
    public Optional<Order> lockById(long id) {
        return jdbc.sql("SELECT * FROM orders WHERE id = :id FOR UPDATE").param("id", id)
                .query(OrderRepository::map).optional();
    }

    public void updateStatus(long id, String status, Long totalAmount, String failureReason) {
        jdbc.sql("""
                        UPDATE orders
                           SET status = :status,
                               total_amount = COALESCE(:total, total_amount),
                               failure_reason = COALESCE(:reason, failure_reason),
                               updated_at = NOW(6)
                         WHERE id = :id
                        """)
                .param("status", status).param("total", totalAmount).param("reason", failureReason).param("id", id)
                .update();
    }

    public List<Item> items(long orderId) {
        return jdbc.sql("SELECT sku, qty FROM order_items WHERE order_id = :id ORDER BY sku").param("id", orderId)
                .query((rs, n) -> new Item(rs.getString("sku"), rs.getInt("qty"))).list();
    }

    public List<Order> recent(int limit) {
        return jdbc.sql("SELECT * FROM orders ORDER BY id DESC LIMIT :limit").param("limit", limit)
                .query(OrderRepository::map).list();
    }

    public void insertEmail(long orderId, String subject) {
        jdbc.sql("INSERT INTO sent_emails (order_id, subject) VALUES (:id, :subject)")
                .param("id", orderId).param("subject", subject).update();
    }

    public int emailCount(long orderId) {
        return jdbc.sql("SELECT COUNT(*) FROM sent_emails WHERE order_id = :id").param("id", orderId)
                .query(Integer.class).single();
    }

    public Map<String, Long> countByStatus() {
        Map<String, Long> counts = new java.util.TreeMap<>();
        jdbc.sql("SELECT status, COUNT(*) AS c FROM orders GROUP BY status")
                .query((rs, n) -> Map.entry(rs.getString("status"), rs.getLong("c")))
                .list().forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    /** Mỗi đơn: trạng thái + số email đã gửi. Dùng để đối soát chéo với inventory và payment. */
    public List<Map<String, Object>> states() {
        return jdbc.sql("""
                        SELECT o.id AS orderId, o.status, o.customer_id AS customerId, o.total_amount AS totalAmount,
                               (SELECT COUNT(*) FROM sent_emails e WHERE e.order_id = o.id) AS emails
                          FROM orders o
                        """)
                .query().listOfRows();
    }

    public void reset() {
        jdbc.sql("DELETE FROM sent_emails").update();
        jdbc.sql("DELETE FROM order_items").update();
        jdbc.sql("DELETE FROM orders").update();
    }

    private static Order map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        // wasNull() chỉ đúng với cột đọc NGAY TRƯỚC nó => phải gọi liền sau getLong("total_amount").
        long total = rs.getLong("total_amount");
        Long totalAmount = rs.wasNull() ? null : total;
        return new Order(rs.getLong("id"), rs.getString("idempotency_key"), rs.getLong("customer_id"),
                rs.getString("status"), totalAmount, rs.getString("failure_reason"));
    }
}
