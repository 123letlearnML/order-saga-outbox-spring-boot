package com.duong.saga.payment;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class PaymentRepository {

    public record Payment(long orderId, long amount, String status) {
    }

    private final JdbcClient jdbc;

    public PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Đọc không khoá (khoá 1 dòng chưa tồn tại sẽ đặt gap lock, xem inventory). */
    public Optional<Payment> find(long orderId) {
        return jdbc.sql("SELECT order_id, amount, status FROM payments WHERE order_id = :id")
                .param("id", orderId)
                .query((rs, n) -> new Payment(rs.getLong("order_id"), rs.getLong("amount"), rs.getString("status")))
                .optional();
    }

    /** Trừ tiền nguyên tử, có điều kiện — cùng kỹ thuật với dự án ví điện tử. 0 dòng => không đủ tiền / không có ví. */
    public int debitIfSufficient(long customerId, long amount) {
        return jdbc.sql("UPDATE wallets SET balance = balance - :amount WHERE customer_id = :id AND balance >= :amount")
                .param("amount", amount).param("id", customerId).update();
    }

    public void insertPayment(long orderId, long customerId, long amount, String status) {
        jdbc.sql("INSERT INTO payments (order_id, customer_id, amount, status) VALUES (:o, :c, :a, :s)")
                .param("o", orderId).param("c", customerId).param("a", amount).param("s", status).update();
    }

    /** Bất biến tiền: tổng ban đầu = tổng số dư hiện tại + tổng đã thu. */
    public Map<String, Object> moneyReport() {
        return jdbc.sql("""
                        SELECT (SELECT SUM(initial_balance) FROM wallets) AS initialTotal,
                               (SELECT SUM(balance) FROM wallets) AS walletTotal,
                               (SELECT COALESCE(SUM(amount), 0) FROM payments WHERE status = 'CHARGED') AS chargedTotal
                        """)
                .query()
                .singleRow();
    }

    public List<Map<String, Object>> paymentStates() {
        return jdbc.sql("SELECT order_id AS orderId, status, amount FROM payments").query().listOfRows();
    }

    public void reset() {
        jdbc.sql("DELETE FROM payments").update();
        jdbc.sql("UPDATE wallets SET balance = initial_balance").update();
    }
}
