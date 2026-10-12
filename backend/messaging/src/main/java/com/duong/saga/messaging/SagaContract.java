package com.duong.saga.messaging;

/**
 * "Hợp đồng" giữa các service: tên service và tên message. Đổi tên ở đây là đổi cho cả 3 bên.
 *
 * <pre>
 *  order ──ReserveStock──────► inventory ──StockReserved / StockRejected──► order
 *  order ──ChargePayment─────► payment   ──PaymentCompleted / PaymentFailed► order
 *  order ──ConfirmReservation► inventory                 (thanh toán xong: chốt hàng)
 *  order ──ReleaseStock──────► inventory                 (thanh toán lỗi: BÙ TRỪ, hoàn kho)
 *  order ──NotifyCustomer────► order                     (gửi email qua nhà cung cấp chập chờn)
 * </pre>
 */
public final class SagaContract {

    public static final String ORDER = "order";
    public static final String INVENTORY = "inventory";
    public static final String PAYMENT = "payment";

    public static final String RESERVE_STOCK = "ReserveStock";
    public static final String STOCK_RESERVED = "StockReserved";
    public static final String STOCK_REJECTED = "StockRejected";
    public static final String CHARGE_PAYMENT = "ChargePayment";
    public static final String PAYMENT_COMPLETED = "PaymentCompleted";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String CONFIRM_RESERVATION = "ConfirmReservation";
    public static final String RELEASE_STOCK = "ReleaseStock";
    public static final String NOTIFY_CUSTOMER = "NotifyCustomer";

    private SagaContract() {
    }
}
