package com.duong.saga.order;

import static com.duong.saga.messaging.SagaContract.INVENTORY;
import static com.duong.saga.messaging.SagaContract.NOTIFY_CUSTOMER;
import static com.duong.saga.messaging.SagaContract.PAYMENT_COMPLETED;
import static com.duong.saga.messaging.SagaContract.PAYMENT_FAILED;
import static com.duong.saga.messaging.SagaContract.RESERVE_STOCK;
import static com.duong.saga.messaging.SagaContract.STOCK_RESERVED;
import static org.assertj.core.api.Assertions.assertThat;

import com.duong.saga.messaging.Message;
import com.duong.saga.messaging.MessageCodec;
import com.duong.saga.messaging.MessageDispatcher;
import com.duong.saga.messaging.MessagingStore;
import com.duong.saga.messaging.OutboxRelay;
import com.duong.saga.order.OrderRepository.Item;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/** Chạy trên MySQL thật. Relay không chạy nền; peers trỏ vào cổng không tồn tại => mọi lần gửi đều lỗi. */
@SpringBootTest
@ActiveProfiles("test")
class OrderSagaTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private OrderRepository orders;
    @Autowired
    private MessageDispatcher dispatcher;
    @Autowired
    private MessagingStore messagingStore;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private MessageCodec codec;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void reset() {
        tx.executeWithoutResult(s -> {
            orders.reset();
            messagingStore.reset();
        });
    }

    @Test
    void orderAndItsOutboxMessageAreSavedInTheSameTransaction() {
        long id = place("k-1").order().id();

        List<Map<String, Object>> outbox = jdbc.sql("SELECT destination, type, order_id, status FROM outbox")
                .query().listOfRows();
        assertThat(outbox).hasSize(1);
        assertThat(outbox.getFirst()).containsEntry("destination", INVENTORY).containsEntry("type", RESERVE_STOCK)
                .containsEntry("status", "PENDING");
        assertThat(((Number) outbox.getFirst().get("order_id")).longValue()).isEqualTo(id);
    }

    @Test
    void sameIdempotencyKeySentConcurrentlyCreatesOneOrder() throws Exception {
        List<OrderService.PlaceOrderResult> results = concurrently(30, () -> place("same-key"));

        assertThat(results).extracting(r -> r.order().id()).containsOnly(results.getFirst().order().id());
        assertThat(results).filteredOn(r -> !r.replayed()).hasSize(1);
        assertThat(count("orders")).isEqualTo(1);
        assertThat(count("outbox")).as("chỉ 1 ReserveStock được ghi").isEqualTo(1);
    }

    @Test
    void duplicateDeliveryIsSuppressedByInbox() {
        long id = place("k-2").order().id();
        Message notify = Message.create(NOTIFY_CUSTOMER, id, "order", Map.of());

        dispatcher.dispatch(notify);
        dispatcher.dispatch(notify); // mạng giao trùng: cùng messageId

        assertThat(orders.emailCount(id)).isEqualTo(1);
        assertThat(messagingStore.counter(MessagingStore.DUPLICATES_SUPPRESSED)).isEqualTo(1);
    }

    @Test
    void lateOrDuplicateEventIsIgnoredByStateGuard() {
        long id = place("k-3").order().id();

        // PaymentCompleted đến khi đơn còn PENDING (sai thứ tự / message cũ) => bỏ qua, không CONFIRMED bừa.
        dispatcher.dispatch(Message.create(PAYMENT_COMPLETED, id, "payment", Map.of()));

        assertThat(orders.findById(id).orElseThrow().status()).isEqualTo("PENDING");
        assertThat(messagingStore.counter(OrderSaga.STALE_EVENTS_IGNORED)).isEqualTo(1);
    }

    @Test
    void paymentFailureTriggersCompensation() {
        long id = place("k-4").order().id();
        dispatcher.dispatch(Message.create(STOCK_RESERVED, id, "inventory", Map.of("totalAmount", 500_000)));
        dispatcher.dispatch(Message.create(PAYMENT_FAILED, id, "payment", Map.of("reason", "INSUFFICIENT")));

        assertThat(orders.findById(id).orElseThrow().status()).isEqualTo("CANCELLED");
        assertThat(jdbc.sql("SELECT type FROM outbox WHERE order_id = :id ORDER BY id").param("id", id)
                .query(String.class).list())
                .containsExactly(RESERVE_STOCK, "ChargePayment", "ReleaseStock");
    }

    @Test
    void unreachablePeerIsRetriedWithBackoffThenDeadLettered() throws Exception {
        place("k-5"); // ReserveStock → inventory (http://localhost:1, không ai nghe)

        for (int i = 0; i < 20 && count("outbox WHERE status = 'DEAD'") == 0; i++) {
            relay.relayOnce();
            Thread.sleep(20); // chờ qua next_attempt_at (backoff 1ms trong test)
        }

        Map<String, Object> row = jdbc.sql("SELECT status, attempts, last_error FROM outbox").query().singleRow();
        assertThat(row).containsEntry("status", "DEAD");
        assertThat(((Number) row.get("attempts")).intValue()).isEqualTo(3); // max-attempts trong test
        assertThat((String) row.get("last_error")).contains("ConnectException");
    }

    @Test
    void redriveGivesTheMessageAFreshRetryWindow() {
        place("k-6");
        // Giả lập: message chết cách đây 2 giờ (quá hạn retry 30 phút từ lâu).
        jdbc.sql("UPDATE outbox SET status = 'DEAD', retry_started_at = NOW(6) - INTERVAL 2 HOUR").update();

        messagingStore.redriveAll();
        relay.relayOnce(); // inventory vẫn chết => lỗi tạm thời

        // Phải được hẹn retry tiếp, KHÔNG rơi lại vào DEAD chỉ vì message "già" (bug chaos test đã bắt được).
        assertThat(jdbc.sql("SELECT status FROM outbox").query(String.class).single()).isEqualTo("PENDING");
    }

    @Test
    void poisonMessageIsDeadLetteredImmediately() {
        Message message = Message.create("Whatever", 1, "order", Map.of());
        tx.executeWithoutResult(s -> messagingStore.insertOutbox("nowhere", message, codec.encode(message)));

        relay.relayOnce();

        Map<String, Object> row = jdbc.sql("SELECT status, attempts FROM outbox").query().singleRow();
        assertThat(row).containsEntry("status", "DEAD");
        assertThat(((Number) row.get("attempts")).intValue()).as("không retry vô ích").isEqualTo(1);
    }

    @Test
    void concurrentRelaysNeverClaimTheSameMessage() throws Exception {
        tx.executeWithoutResult(s -> {
            for (int i = 0; i < 200; i++) {
                Message m = Message.create(RESERVE_STOCK, i, "order", Map.of());
                messagingStore.insertOutbox(INVENTORY, m, codec.encode(m));
            }
        });

        // 8 "relay" cùng nhận lô 50 => SKIP LOCKED chia nhau, không ai chờ ai, không dòng nào bị nhận 2 lần.
        List<List<MessagingStore.OutboxRow>> batches = concurrently(8, () -> messagingStore.claimBatch(50, 30));

        List<Long> claimed = batches.stream().flatMap(List::stream).map(MessagingStore.OutboxRow::id).toList();
        Set<Long> unique = new HashSet<>(claimed);
        assertThat(unique).hasSameSizeAs(claimed);
        assertThat(claimed).hasSize(200);
    }

    private OrderService.PlaceOrderResult place(String key) {
        return orderService.place(key, 1, List.of(new Item("SKU-04", 1)));
    }

    private long count(String tableAndWhere) {
        return jdbc.sql("SELECT COUNT(*) FROM " + tableAndWhere).query(Long.class).single();
    }

    private static <T> List<T> concurrently(int copies, Callable<T> task) throws Exception {
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(copies)) {
            for (int i = 0; i < copies; i++) {
                futures.add(executor.submit(() -> {
                    startGun.await();
                    return task.call();
                }));
            }
            startGun.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) {
                results.add(f.get());
            }
            return results;
        }
    }
}
