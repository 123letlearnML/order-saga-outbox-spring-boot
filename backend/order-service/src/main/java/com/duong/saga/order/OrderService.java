package com.duong.saga.order;

import static com.duong.saga.messaging.SagaContract.INVENTORY;
import static com.duong.saga.messaging.SagaContract.RESERVE_STOCK;

import com.duong.saga.messaging.MessagePublisher;
import com.duong.saga.order.OrderRepository.Item;
import com.duong.saga.order.OrderRepository.Order;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {

    public record PlaceOrderResult(Order order, boolean replayed) {
    }

    private final OrderRepository orders;
    private final MessagePublisher publisher;
    private final TransactionTemplate transactionTemplate;

    public OrderService(OrderRepository orders, MessagePublisher publisher, TransactionTemplate transactionTemplate) {
        this.orders = orders;
        this.publisher = publisher;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Bước 0 của saga. Lưu đơn PENDING và message ReserveStock trong CÙNG 1 transaction,
     * rồi trả 202 ngay — phần còn lại chạy bất đồng bộ giữa các service.
     * Idempotency-Key: app retry khi mạng lỗi sẽ không tạo ra 2 đơn.
     */
    public PlaceOrderResult place(String idempotencyKey, long customerId, List<Item> items) {
        var existing = orders.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return new PlaceOrderResult(existing.get(), true);
        }
        try {
            long orderId = transactionTemplate.execute(status -> {
                long id = orders.insertOrder(idempotencyKey, customerId, items);
                List<Map<String, Object>> payloadItems = items.stream()
                        .map(i -> Map.<String, Object>of("sku", i.sku(), "qty", i.qty()))
                        .toList();
                publisher.publish(INVENTORY, RESERVE_STOCK, id, Map.of("items", payloadItems));
                return id;
            });
            return new PlaceOrderResult(orders.findById(orderId).orElseThrow(), false);
        } catch (DuplicateKeyException e) {
            // 2 request cùng key đến cùng lúc: bên thua trả lại đơn của bên thắng.
            return new PlaceOrderResult(orders.findByIdempotencyKey(idempotencyKey).orElseThrow(), true);
        }
    }
}
