package com.duong.saga.payment;

import static com.duong.saga.messaging.SagaContract.CHARGE_PAYMENT;
import static com.duong.saga.messaging.SagaContract.ORDER;
import static com.duong.saga.messaging.SagaContract.PAYMENT_COMPLETED;
import static com.duong.saga.messaging.SagaContract.PAYMENT_FAILED;

import com.duong.saga.messaging.Message;
import com.duong.saga.messaging.MessageHandler;
import com.duong.saga.messaging.MessagePublisher;
import com.duong.saga.payment.PaymentRepository.Payment;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Bước 2 của saga: thu tiền. Kết quả luôn được trả lời về order (thành công hay thất bại). */
@Component
public class ChargePaymentHandler implements MessageHandler {

    private final PaymentRepository repository;
    private final MessagePublisher publisher;
    private final int declinePercent;

    public ChargePaymentHandler(PaymentRepository repository,
                                MessagePublisher publisher,
                                @Value("${payment.decline-percent}") int declinePercent) {
        this.repository = repository;
        this.publisher = publisher;
        this.declinePercent = declinePercent;
    }

    @Override
    public String type() {
        return CHARGE_PAYMENT;
    }

    @Override
    public void handle(Message message) {
        long orderId = message.orderId();

        // Đã xử lý đơn này rồi (message trùng) => chỉ trả lời lại kết quả cũ, KHÔNG trừ tiền lần 2.
        Optional<Payment> existing = repository.find(orderId);
        if (existing.isPresent()) {
            reply(orderId, existing.get().status());
            return;
        }

        long customerId = message.getLong("customerId");
        long amount = message.getLong("amount");
        String status;
        if (isDeclinedByBank(orderId)) {
            status = "DECLINED";
        } else if (repository.debitIfSufficient(customerId, amount) == 0) {
            status = "INSUFFICIENT";
        } else {
            status = "CHARGED";
        }
        // PK order_id: 2 bản trùng chạy song song thì bản sau nổ duplicate key => rollback cả phần trừ tiền.
        repository.insertPayment(orderId, customerId, amount, status);
        reply(orderId, status);
    }

    private void reply(long orderId, String status) {
        if ("CHARGED".equals(status)) {
            publisher.publish(ORDER, PAYMENT_COMPLETED, orderId, Map.of());
        } else {
            publisher.publish(ORDER, PAYMENT_FAILED, orderId, Map.of("reason", status));
        }
    }

    /** Giả lập ngân hàng từ chối thẻ. Cố định theo orderId: giao lại bao nhiêu lần cũng cùng kết quả. */
    private boolean isDeclinedByBank(long orderId) {
        return Math.floorMod(Long.hashCode(orderId * 0x9E3779B97F4A7C15L), 100) < declinePercent;
    }
}
