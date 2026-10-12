package com.duong.saga.order;

import com.duong.saga.messaging.MessagingStore;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/orders")
public class OrderAdminController {

    private static final Set<String> TERMINAL = Set.of("CONFIRMED", "REJECTED", "CANCELLED");

    /**
     * @param stuck              đơn chưa tới trạng thái cuối — sau khi hệ thống "ráo" phải = 0
     * @param confirmedNoEmail   đơn CONFIRMED mà khách chưa nhận email
     * @param duplicateEmails    số email thừa (khách nhận 2+ email cho 1 đơn)
     */
    public record Summary(Map<String, Long> byStatus, long stuck, long confirmedNoEmail, long duplicateEmails,
                          long staleEventsIgnored) {
    }

    private final OrderRepository orders;
    private final MessagingStore messagingStore;

    public OrderAdminController(OrderRepository orders, MessagingStore messagingStore) {
        this.orders = orders;
        this.messagingStore = messagingStore;
    }

    @GetMapping("/summary")
    @Transactional(readOnly = true)
    public Summary summary() {
        List<Map<String, Object>> states = orders.states();
        long stuck = states.stream().filter(s -> !TERMINAL.contains((String) s.get("status"))).count();
        long confirmedNoEmail = states.stream()
                .filter(s -> "CONFIRMED".equals(s.get("status")) && emails(s) == 0).count();
        long duplicateEmails = states.stream().mapToLong(s -> Math.max(0, emails(s) - 1)).sum();
        return new Summary(orders.countByStatus(), stuck, confirmedNoEmail, duplicateEmails,
                messagingStore.counter(OrderSaga.STALE_EVENTS_IGNORED));
    }

    /** Trạng thái mọi đơn — chaos test dùng để đối soát chéo với inventory và payment. */
    @GetMapping("/states")
    public List<Map<String, Object>> states() {
        return orders.states();
    }

    @PostMapping("/reset")
    @Transactional
    public Summary reset() {
        orders.reset();
        messagingStore.reset();
        return summary();
    }

    private static long emails(Map<String, Object> s) {
        return ((Number) s.get("emails")).longValue();
    }
}
