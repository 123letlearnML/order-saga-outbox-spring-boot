package com.duong.saga.order;

import static com.duong.saga.messaging.SagaContract.NOTIFY_CUSTOMER;

import com.duong.saga.messaging.Message;
import com.duong.saga.messaging.MessageHandler;
import com.duong.saga.messaging.TransientMessageException;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Gửi email xác nhận qua nhà cung cấp bên ngoài (giả lập).
 * <ul>
 *   <li>Nhà cung cấp chập chờn: {@code failure-percent}% số lần gọi lỗi tạm thời => 503 => relay retry có backoff.</li>
 *   <li>Nhà cung cấp KHÔNG idempotent: gọi 2 lần = khách nhận 2 email. Không có trạng thái nào để "chặn theo
 *       nghiệp vụ" ở đây — chỉ có Inbox mới chặn được message giao trùng.</li>
 * </ul>
 */
@Component
public class NotifyCustomerHandler implements MessageHandler {

    private final OrderRepository orders;
    private final int failurePercent;

    public NotifyCustomerHandler(OrderRepository orders, @Value("${notification.failure-percent}") int failurePercent) {
        this.orders = orders;
        this.failurePercent = failurePercent;
    }

    @Override
    public String type() {
        return NOTIFY_CUSTOMER;
    }

    @Override
    public void handle(Message message) {
        if (ThreadLocalRandom.current().nextInt(100) < failurePercent) {
            throw new TransientMessageException("Email provider timeout");
        }
        orders.insertEmail(message.orderId(), "Đơn hàng #" + message.orderId() + " đã được xác nhận");
    }
}
