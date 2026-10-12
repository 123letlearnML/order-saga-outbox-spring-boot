package com.duong.saga.inventory;

import static com.duong.saga.messaging.SagaContract.RELEASE_STOCK;

import com.duong.saga.inventory.InventoryRepository.ReservationItem;
import com.duong.saga.messaging.Message;
import com.duong.saga.messaging.MessageHandler;
import org.springframework.stereotype.Component;

/**
 * BÙ TRỪ (compensation): thanh toán thất bại => trả hàng đã giữ về kho.
 * Saga không có "rollback" như transaction; mỗi bước đã commit phải có 1 bước ngược lại viết tay.
 */
@Component
public class ReleaseStockHandler implements MessageHandler {

    private final InventoryRepository repository;

    public ReleaseStockHandler(InventoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public String type() {
        return RELEASE_STOCK;
    }

    @Override
    public void handle(Message message) {
        long orderId = message.orderId();
        // Chỉ hoàn kho khi đang RESERVED. Nhận ReleaseStock lần 2 => đã RELEASED => bỏ qua.
        // Không có kiểm tra này thì message trùng sẽ cộng kho 2 lần: hàng "tự sinh ra".
        if (repository.lockReservationStatus(orderId).filter("RESERVED"::equals).isEmpty()) {
            return;
        }
        for (ReservationItem item : repository.itemsSortedBySku(orderId)) {
            repository.increase(item.sku(), item.qty());
        }
        repository.updateStatus(orderId, "RELEASED");
    }
}
