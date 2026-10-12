package com.duong.saga.payment;

import static com.duong.saga.messaging.SagaContract.CHARGE_PAYMENT;
import static org.assertj.core.api.Assertions.assertThat;

import com.duong.saga.messaging.Message;
import com.duong.saga.messaging.MessageDispatcher;
import com.duong.saga.messaging.MessagingStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
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
class PaymentSagaTest {

    private static final long CUSTOMER = 1; // ví 50.000.000

    @Autowired
    private MessageDispatcher dispatcher;
    @Autowired
    private PaymentRepository repository;
    @Autowired
    private MessagingStore messagingStore;
    @Autowired
    private PaymentAdminController admin;
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
    void sameMessageDeliveredConcurrentlyChargesOnce() throws Exception {
        Message charge = charge(100, 1_000_000);

        concurrently(20, () -> dispatcher.dispatch(charge));

        assertThat(balance()).isEqualTo(49_000_000);
        assertThat(messagingStore.counter(MessagingStore.DUPLICATES_SUPPRESSED)).isEqualTo(19);
        assertThat(admin.money().consistent()).isTrue();
    }

    @Test
    void differentMessagesForTheSameOrderStillChargeOnce() throws Exception {
        // Inbox không chặn được (messageId khác nhau). Lớp chặn thứ 2: kiểm tra payments + PK order_id.
        int failures = concurrently(20, () -> dispatcher.dispatch(charge(200, 1_000_000)));

        assertThat(balance()).isEqualTo(49_000_000);
        assertThat(jdbc.sql("SELECT COUNT(*) FROM payments").query(Long.class).single()).isEqualTo(1);
        // Bản thua cuộc đua bị duplicate key => rollback cả phần trừ tiền => trả 5xx => bên gửi retry, lần sau thấy đã trả.
        assertThat(failures).isLessThan(20);
        assertThat(admin.money().consistent()).isTrue();
    }

    @Test
    void insufficientFundsIsRecordedAndReported() {
        dispatcher.dispatch(Message.create(CHARGE_PAYMENT, 300, "order", Map.of("customerId", 10, "amount", 1_000_000)));

        assertThat(jdbc.sql("SELECT status FROM payments WHERE order_id = 300").query(String.class).single())
                .isEqualTo("INSUFFICIENT");
        assertThat(jdbc.sql("SELECT type FROM outbox").query(String.class).single()).isEqualTo("PaymentFailed");
    }

    private static Message charge(long orderId, long amount) {
        return Message.create(CHARGE_PAYMENT, orderId, "order", Map.of("customerId", CUSTOMER, "amount", amount));
    }

    private long balance() {
        return jdbc.sql("SELECT balance FROM wallets WHERE customer_id = :c").param("c", CUSTOMER)
                .query(Long.class).single();
    }

    /** @return số lần dispatch ném exception */
    private static int concurrently(int copies, Runnable task) throws Exception {
        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(copies)) {
            for (int i = 0; i < copies; i++) {
                futures.add(executor.submit((Callable<Void>) () -> {
                    startGun.await();
                    task.run();
                    return null;
                }));
            }
            startGun.countDown();
            int failures = 0;
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (ExecutionException e) {
                    failures++;
                }
            }
            return failures;
        }
    }
}
