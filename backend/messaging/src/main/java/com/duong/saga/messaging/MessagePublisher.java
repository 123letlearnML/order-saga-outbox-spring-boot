package com.duong.saga.messaging;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Điểm duy nhất để code nghiệp vụ gửi message. Nghiệp vụ không biết bên dưới là outbox hay gửi thẳng —
 * sau này thay bằng Kafka cũng chỉ sửa ở đây.
 */
@Component
public class MessagePublisher {

    private static final Logger log = LoggerFactory.getLogger(MessagePublisher.class);

    private final SagaProperties properties;
    private final MessagingStore store;
    private final MessageCodec codec;
    private final MessageSender sender;
    private final ExecutorService naiveExecutor = Executors.newFixedThreadPool(16);

    public MessagePublisher(SagaProperties properties, MessagingStore store, MessageCodec codec, MessageSender sender) {
        this.properties = properties;
        this.store = store;
        this.codec = codec;
        this.sender = sender;
    }

    /** Phải gọi bên trong transaction nghiệp vụ. */
    public void publish(String destination, String type, long orderId, Map<String, Object> payload) {
        Message message = Message.create(type, orderId, properties.service(), payload);
        String body = codec.encode(message);
        if (properties.mode() == SagaMode.OUTBOX) {
            // Cùng transaction với thay đổi nghiệp vụ: hoặc cả 2 cùng commit, hoặc cả 2 cùng rollback.
            store.insertOutbox(destination, message, body);
        } else {
            sendAfterCommit(destination, body);
        }
    }

    /**
     * CÁCH NGÂY THƠ (dual-write): đợi DB commit xong rồi gửi HTTP.
     * <pre>
     *   COMMIT  ✓   ← đơn hàng đã lưu
     *   ─── process bị kill ở đây, hoặc bên nhận đang chết ───
     *   gửi HTTP ✗   ← message mất vĩnh viễn, không ai biết để gửi lại
     * </pre>
     * Retry 1 lần trong RAM cũng không cứu được: process chết là hàng đợi trong RAM mất theo.
     */
    private void sendAfterCommit(String destination, String body) {
        Runnable send = () -> naiveExecutor.submit(() -> {
            MessageSender.Result result = sender.send(destination, body);
            if (result.outcome() == MessageSender.Outcome.RETRYABLE) {
                sleep(500);
                result = sender.send(destination, body);
            }
            if (result.outcome() != MessageSender.Outcome.DELIVERED) {
                log.warn("NAIVE send to {} failed, message lost: {}", destination, result.error());
                store.increment(MessagingStore.NAIVE_SEND_FAILURES);
            }
        });
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
