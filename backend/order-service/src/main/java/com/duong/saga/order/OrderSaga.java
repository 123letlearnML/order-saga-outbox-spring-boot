package com.duong.saga.order;

import static com.duong.saga.messaging.SagaContract.CHARGE_PAYMENT;
import static com.duong.saga.messaging.SagaContract.CONFIRM_RESERVATION;
import static com.duong.saga.messaging.SagaContract.INVENTORY;
import static com.duong.saga.messaging.SagaContract.NOTIFY_CUSTOMER;
import static com.duong.saga.messaging.SagaContract.ORDER;
import static com.duong.saga.messaging.SagaContract.PAYMENT;
import static com.duong.saga.messaging.SagaContract.PAYMENT_COMPLETED;
import static com.duong.saga.messaging.SagaContract.PAYMENT_FAILED;
import static com.duong.saga.messaging.SagaContract.RELEASE_STOCK;
import static com.duong.saga.messaging.SagaContract.STOCK_REJECTED;
import static com.duong.saga.messaging.SagaContract.STOCK_RESERVED;

import com.duong.saga.messaging.Message;
import com.duong.saga.messaging.MessageHandler;
import com.duong.saga.messaging.MessagePublisher;
import com.duong.saga.messaging.MessagingStore;
import com.duong.saga.messaging.SimpleMessageHandler;
import com.duong.saga.order.OrderRepository.Order;
import java.util.Map;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Bộ điều phối saga (orchestration): nhận kết quả từng bước, quyết định bước tiếp theo hoặc bù trừ.
 *
 * <p>Mỗi chuyển trạng thái có "chốt chặn trạng thái" (state guard): chỉ chuyển khi đơn đang ở đúng trạng thái
 * mong đợi. Message trùng, message đến muộn, message cũ => đơn đã ở trạng thái khác => bỏ qua.
 * Đây là lớp chống trùng thứ 2, sau Inbox.
 */
@Configuration
public class OrderSaga {

    public static final String STALE_EVENTS_IGNORED = "stale_events_ignored";

    private final OrderRepository orders;
    private final MessagePublisher publisher;
    private final MessagingStore messagingStore;

    public OrderSaga(OrderRepository orders, MessagePublisher publisher, MessagingStore messagingStore) {
        this.orders = orders;
        this.publisher = publisher;
        this.messagingStore = messagingStore;
    }

    // Mỗi handler là 1 bean riêng: Spring gom mọi bean MessageHandler vào List của dispatcher.
    // (Khai báo 1 bean kiểu List<MessageHandler> sẽ KHÔNG được gộp khi đã có bean MessageHandler khác.)
    @Bean
    MessageHandler stockReservedHandler() {
        return new SimpleMessageHandler(STOCK_RESERVED, this::onStockReserved);
    }

    @Bean
    MessageHandler stockRejectedHandler() {
        return new SimpleMessageHandler(STOCK_REJECTED, this::onStockRejected);
    }

    @Bean
    MessageHandler paymentCompletedHandler() {
        return new SimpleMessageHandler(PAYMENT_COMPLETED, this::onPaymentCompleted);
    }

    @Bean
    MessageHandler paymentFailedHandler() {
        return new SimpleMessageHandler(PAYMENT_FAILED, this::onPaymentFailed);
    }

    /** Giữ hàng xong → thu tiền. */
    void onStockReserved(Message m) {
        guarded(m, "PENDING").ifPresent(order -> {
            long total = m.getLong("totalAmount");
            orders.updateStatus(order.id(), "STOCK_RESERVED", total, null);
            publisher.publish(PAYMENT, CHARGE_PAYMENT, order.id(),
                    Map.of("customerId", order.customerId(), "amount", total));
        });
    }

    /** Hết hàng → từ chối. Chưa bước nào commit thay đổi ở service khác nên không cần bù trừ. */
    void onStockRejected(Message m) {
        guarded(m, "PENDING").ifPresent(order ->
                orders.updateStatus(order.id(), "REJECTED", null, m.getString("reason")));
    }

    /** Thu tiền xong → chốt hàng + gửi email (2 message, cùng 1 transaction với việc đổi trạng thái). */
    void onPaymentCompleted(Message m) {
        guarded(m, "STOCK_RESERVED").ifPresent(order -> {
            orders.updateStatus(order.id(), "CONFIRMED", null, null);
            publisher.publish(INVENTORY, CONFIRM_RESERVATION, order.id(), Map.of());
            publisher.publish(ORDER, NOTIFY_CUSTOMER, order.id(), Map.of());
        });
    }

    /** Thu tiền thất bại → BÙ TRỪ: hàng đã giữ ở inventory phải được trả lại kho. */
    void onPaymentFailed(Message m) {
        guarded(m, "STOCK_RESERVED").ifPresent(order -> {
            orders.updateStatus(order.id(), "CANCELLED", null, m.getString("reason"));
            publisher.publish(INVENTORY, RELEASE_STOCK, order.id(), Map.of());
        });
    }

    private Optional<Order> guarded(Message m, String expectedStatus) {
        Optional<Order> order = orders.lockById(m.orderId()).filter(o -> o.status().equals(expectedStatus));
        if (order.isEmpty()) {
            messagingStore.increment(STALE_EVENTS_IGNORED);
        }
        return order;
    }
}
