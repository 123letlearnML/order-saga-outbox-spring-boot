package com.duong.saga.inventory;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Toàn bộ SQL của kho, viết tay để thấy rõ chỗ nào khoá và khoá theo thứ tự nào. */
@Repository
public class InventoryRepository {

    public record Product(String sku, long price, int available) {
    }

    public record ReservationItem(String sku, int qty) {
    }

    private final JdbcClient jdbc;

    public InventoryRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Khoá các sản phẩm của đơn THEO THỨ TỰ SKU (ORDER BY sku FOR UPDATE).
     * Đơn A (SKU-01, SKU-02) và đơn B (SKU-02, SKU-01) cùng lúc vẫn khoá SKU-01 trước => không deadlock.
     */
    public List<Product> lockProductsOrdered(Collection<String> skus) {
        return jdbc.sql("SELECT sku, price, available FROM products WHERE sku IN (:skus) ORDER BY sku FOR UPDATE")
                .param("skus", skus)
                .query((rs, n) -> new Product(rs.getString("sku"), rs.getLong("price"), rs.getInt("available")))
                .list();
    }

    public void decrease(String sku, int qty) {
        jdbc.sql("UPDATE products SET available = available - :qty WHERE sku = :sku")
                .param("qty", qty).param("sku", sku).update();
    }

    public void increase(String sku, int qty) {
        jdbc.sql("UPDATE products SET available = available + :qty WHERE sku = :sku")
                .param("qty", qty).param("sku", sku).update();
    }

    /**
     * Đọc trạng thái KHÔNG khoá. Dùng để kiểm tra "đã giữ chỗ chưa" trước khi INSERT.
     * Không dùng FOR UPDATE ở đây: khoá 1 dòng CHƯA TỒN TẠI ở REPEATABLE READ sẽ đặt gap lock; 2 đơn mới cùng lúc
     * giữ chung gap rồi chặn INSERT của nhau => deadlock. Trùng thật thì PK order_id chặn ở bước INSERT.
     */
    public Optional<String> reservationStatus(long orderId) {
        return jdbc.sql("SELECT status FROM reservations WHERE order_id = :id")
                .param("id", orderId)
                .query(String.class)
                .optional();
    }

    /** Khoá reservation ĐÃ TỒN TẠI của 1 đơn (record lock). Trả về trạng thái hiện tại, rỗng nếu chưa có. */
    public Optional<String> lockReservationStatus(long orderId) {
        return jdbc.sql("SELECT status FROM reservations WHERE order_id = :id FOR UPDATE")
                .param("id", orderId)
                .query(String.class)
                .optional();
    }

    public Optional<Long> reservationAmount(long orderId) {
        return jdbc.sql("SELECT total_amount FROM reservations WHERE order_id = :id")
                .param("id", orderId).query(Long.class).optional();
    }

    public void insertReservation(long orderId, long totalAmount, List<ReservationItem> items) {
        jdbc.sql("INSERT INTO reservations (order_id, status, total_amount) VALUES (:id, 'RESERVED', :amount)")
                .param("id", orderId).param("amount", totalAmount).update();
        for (ReservationItem item : items) {
            jdbc.sql("INSERT INTO reservation_items (order_id, sku, qty) VALUES (:id, :sku, :qty)")
                    .param("id", orderId).param("sku", item.sku()).param("qty", item.qty()).update();
        }
    }

    public List<ReservationItem> itemsSortedBySku(long orderId) {
        return jdbc.sql("SELECT sku, qty FROM reservation_items WHERE order_id = :id ORDER BY sku")
                .param("id", orderId)
                .query((rs, n) -> new ReservationItem(rs.getString("sku"), rs.getInt("qty")))
                .list();
    }

    public void updateStatus(long orderId, String status) {
        jdbc.sql("UPDATE reservations SET status = :status, updated_at = NOW(6) WHERE order_id = :id")
                .param("status", status).param("id", orderId).update();
    }

    /** Mỗi SKU: tồn ban đầu, còn lại, đang giữ chỗ, đã bán. Bất biến: initial = available + reserved + confirmed. */
    public List<Map<String, Object>> stockReport() {
        return jdbc.sql("""
                        SELECT p.sku, p.initial_stock, p.available,
                               COALESCE(SUM(CASE WHEN r.status = 'RESERVED'  THEN i.qty END), 0) AS reserved,
                               COALESCE(SUM(CASE WHEN r.status = 'CONFIRMED' THEN i.qty END), 0) AS confirmed
                          FROM products p
                          LEFT JOIN reservation_items i ON i.sku = p.sku
                          LEFT JOIN reservations r ON r.order_id = i.order_id
                         GROUP BY p.sku, p.initial_stock, p.available
                         ORDER BY p.sku
                        """)
                .query()
                .listOfRows();
    }

    public List<Map<String, Object>> reservationStates() {
        return jdbc.sql("SELECT order_id AS orderId, status FROM reservations").query().listOfRows();
    }

    public void reset() {
        jdbc.sql("DELETE FROM reservation_items").update();
        jdbc.sql("DELETE FROM reservations").update();
        jdbc.sql("UPDATE products SET available = initial_stock").update();
    }
}
