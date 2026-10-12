package com.duong.saga.inventory;

import static com.duong.saga.messaging.SagaContract.CONFIRM_RESERVATION;

import com.duong.saga.messaging.Message;
import com.duong.saga.messaging.MessageHandler;
import org.springframework.stereotype.Component;

/** Thanh toán xong: hàng đang giữ chỗ chuyển thành đã bán. */
@Component
public class ConfirmReservationHandler implements MessageHandler {

    private final InventoryRepository repository;

    public ConfirmReservationHandler(InventoryRepository repository) {
        this.repository = repository;
    }

    @Override
    public String type() {
        return CONFIRM_RESERVATION;
    }

    @Override
    public void handle(Message message) {
        // Chỉ RESERVED → CONFIRMED. Trạng thái khác (đã chốt, đã hoàn) => message cũ/trùng => bỏ qua.
        repository.lockReservationStatus(message.orderId())
                .filter("RESERVED"::equals)
                .ifPresent(s -> repository.updateStatus(message.orderId(), "CONFIRMED"));
    }
}
