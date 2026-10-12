package com.duong.saga.inventory;

import static com.duong.saga.messaging.SagaContract.RELEASE_STOCK;
import static com.duong.saga.messaging.SagaContract.RESERVE_STOCK;
import static org.assertj.core.api.Assertions.assertThat;

import com.duong.saga.messaging.Message;
import com.duong.saga.messaging.MessageDispatcher;
import com.duong.saga.messaging.MessagingStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

@SpringBootTest
@ActiveProfiles("test")
class InventorySagaTest {

    @Autowired
    private MessageDispatcher dispatcher;
    @Autowired
    private InventoryRepository repository;
    @Autowired
    private MessagingStore messagingStore;
    @Autowired
    private InventoryAdminController admin;
    @Autowired
    private JdbcClient jdbc;
    @Autowired
    private TransactionTemplate tx;

    @BeforeEach
    void reset() {
        tx.executeWithoutResult(s -> {
            repository.reset();
            messagingStore.reset();
        });
    }

    @Test
    void concurrentReservationsNeverOversellScarceStock() throws Exception {
        // SKU-01 chỉ có 300 cái; 400 đơn cùng giành, mỗi đơn 1 cái, kèm 1 mặt hàng khác để khoá nhiều dòng.
        List<Callable<Void>> tasks = new ArrayList<>();
        for (long orderId = 1; orderId <= 400; orderId++) {
            Message m = reserve(orderId, List.of(Map.of("sku", "SKU-04", "qty", 1), Map.of("sku", "SKU-01", "qty", 1)));
            tasks.add(() -> {
                dispatcher.dispatch(m);
                return null;
            });
        }
        concurrently(tasks);

        assertThat(available("SKU-01")).isZero();
        assertThat(count("reservations")).isEqualTo(300);
        assertThat(count("outbox WHERE type = 'StockReserved'")).isEqualTo(300);
        assertThat(count("outbox WHERE type = 'StockRejected'")).isEqualTo(100);
        assertThat(admin.stock().consistent()).isTrue();
    }

    @Test
    void duplicateDeliveriesOfTheSameMessageReserveOnce() throws Exception {
        Message m = reserve(42, List.of(Map.of("sku", "SKU-02", "qty", 3)));

        concurrently(java.util.Collections.nCopies(10, () -> {
            dispatcher.dispatch(m);
            return null;
        }));

        assertThat(available("SKU-02")).isEqualTo(297);
        assertThat(messagingStore.counter(MessagingStore.DUPLICATES_SUPPRESSED)).isEqualTo(9);
    }

    @Test
    void releasingTwiceReturnsStockOnce() {
        dispatcher.dispatch(reserve(7, List.of(Map.of("sku", "SKU-03", "qty", 5))));
        assertThat(available("SKU-03")).isEqualTo(295);

        // 2 message ReleaseStock KHÁC messageId (inbox không chặn được) => state guard phải chặn.
        dispatcher.dispatch(Message.create(RELEASE_STOCK, 7, "order", Map.of()));
        dispatcher.dispatch(Message.create(RELEASE_STOCK, 7, "order", Map.of()));

        assertThat(available("SKU-03")).as("hoàn kho đúng 1 lần").isEqualTo(300);
        assertThat(admin.stock().consistent()).isTrue();
    }

    private static Message reserve(long orderId, List<Map<String, Object>> items) {
        return Message.create(RESERVE_STOCK, orderId, "order", Map.of("items", items));
    }

    private int available(String sku) {
        return jdbc.sql("SELECT available FROM products WHERE sku = :sku").param("sku", sku).query(Integer.class).single();
    }

    private long count(String tableAndWhere) {
        return jdbc.sql("SELECT COUNT(*) FROM " + tableAndWhere).query(Long.class).single();
    }

    private static void concurrently(List<Callable<Void>> tasks) throws Exception {
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<Void>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(32)) {
            for (Callable<Void> task : tasks) {
                futures.add(executor.submit(() -> {
                    startGun.await();
                    return task.call();
                }));
            }
            startGun.countDown();
            for (Future<Void> f : futures) {
                f.get();
            }
        }
    }
}
