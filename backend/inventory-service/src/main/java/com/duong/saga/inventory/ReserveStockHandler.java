package com.duong.saga.inventory;

import static com.duong.saga.messaging.SagaContract.ORDER;
import static com.duong.saga.messaging.SagaContract.RESERVE_STOCK;
import static com.duong.saga.messaging.SagaContract.STOCK_REJECTED;
import static com.duong.saga.messaging.SagaContract.STOCK_RESERVED;

import com.duong.saga.inventory.InventoryRepository.Product;
import com.duong.saga.inventory.InventoryRepository.ReservationItem;
import com.duong.saga.messaging.Message;
import com.duong.saga.messaging.MessageHandler;
import com.duong.saga.messaging.MessagePublisher;
import com.duong.saga.messaging.NonRetryableMessageException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/**
 * Bước 1 của saga: giữ chỗ hàng cho đơn. Hoặc giữ được TẤT CẢ mặt hàng, hoặc không giữ gì (cùng 1 transaction).
 */
@Component
public class ReserveStockHandler implements MessageHandler {

    private final InventoryRepository repository;
    private final MessagePublisher publisher;

    public ReserveStockHandler(InventoryRepository repository, MessagePublisher publisher) {
        this.repository = repository;
        this.publisher = publisher;
    }

    @Override
    public String type() {
        return RESERVE_STOCK;
    }

    @Override
    public void handle(Message message) {
        long orderId = message.orderId();

        // Chống trùng theo nghiệp vụ (lớp 2, sau inbox): đơn này đã được giữ chỗ thì chỉ trả lời lại.
        Optional<String> existing = repository.reservationStatus(orderId);
        if (existing.isPresent()) {
            if (!"RELEASED".equals(existing.get())) {
                long amount = repository.reservationAmount(orderId).orElseThrow();
                publisher.publish(ORDER, STOCK_RESERVED, orderId, Map.of("totalAmount", amount));
            }
            return;
        }

        Map<String, Integer> wanted = parseItems(message); // TreeMap: đã sắp theo SKU
        List<Product> locked = repository.lockProductsOrdered(wanted.keySet());
        if (locked.size() != wanted.size()) {
            reject(orderId, "UNKNOWN_SKU");
            return;
        }
        for (Product p : locked) {
            if (p.available() < wanted.get(p.sku())) {
                reject(orderId, "OUT_OF_STOCK:" + p.sku());
                return;
            }
        }

        long total = 0;
        for (Product p : locked) {
            int qty = wanted.get(p.sku());
            repository.decrease(p.sku(), qty);
            total += p.price() * qty;
        }
        List<ReservationItem> items = wanted.entrySet().stream()
                .map(e -> new ReservationItem(e.getKey(), e.getValue()))
                .toList();
        repository.insertReservation(orderId, total, items);
        publisher.publish(ORDER, STOCK_RESERVED, orderId, Map.of("totalAmount", total));
    }

    private void reject(long orderId, String reason) {
        publisher.publish(ORDER, STOCK_REJECTED, orderId, Map.of("reason", reason));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> parseItems(Message message) {
        if (!(message.get("items") instanceof List<?> raw) || raw.isEmpty()) {
            throw new NonRetryableMessageException("ReserveStock without items");
        }
        Map<String, Integer> wanted = new TreeMap<>();
        for (Object o : raw) {
            Map<String, Object> item = (Map<String, Object>) o;
            String sku = String.valueOf(item.get("sku"));
            int qty = ((Number) item.get("qty")).intValue();
            if (qty <= 0) {
                throw new NonRetryableMessageException("Invalid qty for " + sku);
            }
            wanted.merge(sku, qty, Integer::sum);
        }
        return wanted;
    }
}
