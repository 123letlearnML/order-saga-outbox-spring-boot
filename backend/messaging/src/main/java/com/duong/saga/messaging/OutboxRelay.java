package com.duong.saga.messaging;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * Relay: luồng nền đọc outbox và gửi đi tới khi thành công.
 * <pre>
 *   loop:
 *     lô = claimBatch()                 -- SKIP LOCKED + lease, transaction ngắn
 *     gửi song song từng message        -- KHÔNG giữ khoá DB trong lúc gọi mạng
 *       2xx  → SENT
 *       4xx  → DEAD ngay (message hỏng, retry vô ích)
 *       5xx / timeout → hẹn lại sau backoff; quá maxAttempts hoặc quá maxRetrySeconds → DEAD
 * </pre>
 * Đảm bảo <b>at-least-once</b>: relay có thể chết SAU khi bên kia nhận nhưng TRƯỚC khi đánh dấu SENT
 * => message được gửi lại => bên nhận bắt buộc phải chống trùng (Inbox).
 */
@Component
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final SagaProperties properties;
    private final MessagingStore store;
    private final MessageSender sender;
    private volatile boolean running;
    private Thread loopThread;
    private ExecutorService sendExecutor;

    public OutboxRelay(SagaProperties properties, MessagingStore store, MessageSender sender) {
        this.properties = properties;
        this.store = store;
        this.sender = sender;
    }

    @Override
    public void start() {
        if (properties.mode() != SagaMode.OUTBOX || !properties.relay().enabled()) {
            log.info("Outbox relay disabled (mode={}, enabled={})", properties.mode(), properties.relay().enabled());
            return;
        }
        sendExecutor = Executors.newFixedThreadPool(properties.relay().sendConcurrency());
        running = true;
        loopThread = new Thread(this::loop, "outbox-relay");
        loopThread.setDaemon(true);
        loopThread.start();
        log.info("Outbox relay started");
    }

    @Override
    public void stop() {
        running = false;
        if (loopThread != null) {
            loopThread.interrupt();
        }
        if (sendExecutor != null) {
            sendExecutor.shutdownNow();
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void loop() {
        while (running) {
            try {
                int sent = relayOnce();
                if (sent == 0) {
                    Thread.sleep(properties.relay().pollIntervalMs());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                // DB tạm mất kết nối... => nghỉ rồi thử lại, relay không được chết.
                log.warn("Relay iteration failed: {}", e.getMessage());
                sleepQuietly(1000);
            }
        }
    }

    /** Một vòng relay. Public để test gọi trực tiếp mà không cần luồng nền. */
    public int relayOnce() {
        SagaProperties.Relay cfg = properties.relay();
        List<MessagingStore.OutboxRow> batch = store.claimBatch(cfg.batchSize(), cfg.leaseSeconds());
        if (batch.isEmpty()) {
            return 0;
        }
        if (sendExecutor == null) {
            batch.forEach(this::deliver); // gọi từ test, không có luồng nền
            return batch.size();
        }
        CompletableFuture<?>[] sends = batch.stream()
                .map(row -> CompletableFuture.runAsync(() -> deliver(row), sendExecutor))
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(sends).join();
        return batch.size();
    }

    private void deliver(MessagingStore.OutboxRow row) {
        MessageSender.Result result = sender.send(row.destination(), row.body());
        switch (result.outcome()) {
            case DELIVERED -> store.markSent(row.id());
            case NON_RETRYABLE -> {
                log.warn("Outbox {} is poison, moved to DEAD: {}", row.id(), result.error());
                store.markDead(row.id(), result.error());
            }
            case RETRYABLE -> {
                SagaProperties.Relay cfg = properties.relay();
                if (row.attempts() >= cfg.maxAttempts() || row.ageSeconds() >= cfg.maxRetrySeconds()) {
                    log.warn("Outbox {} gave up after {} attempts / {}s, moved to DEAD",
                            row.id(), row.attempts(), row.ageSeconds());
                    store.markDead(row.id(), result.error());
                } else {
                    store.scheduleRetry(row.id(), backoffMs(row.attempts()), result.error());
                }
            }
        }
    }

    /**
     * Exponential backoff + jitter: 200ms, 400ms, 800ms... tối đa 5s, cộng ngẫu nhiên.
     * Không có jitter thì cả nghìn message cùng retry đúng 1 thời điểm và đè chết service vừa sống lại.
     */
    long backoffMs(int attempts) {
        SagaProperties.Relay cfg = properties.relay();
        long exp = cfg.baseBackoffMs() * (1L << Math.min(attempts - 1, 20));
        long capped = Math.min(cfg.maxBackoffMs(), exp);
        return capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
