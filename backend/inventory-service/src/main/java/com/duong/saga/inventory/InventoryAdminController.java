package com.duong.saga.inventory;

import com.duong.saga.messaging.MessagingStore;
import java.util.List;
import java.util.Map;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class InventoryAdminController {

    /** @param consistent mọi SKU thoả initial = available + reserved + confirmed */
    public record StockReport(List<Map<String, Object>> products, boolean consistent) {
    }

    private final InventoryRepository repository;
    private final MessagingStore messagingStore;

    public InventoryAdminController(InventoryRepository repository, MessagingStore messagingStore) {
        this.repository = repository;
        this.messagingStore = messagingStore;
    }

    @GetMapping("/inventory")
    @Transactional(readOnly = true)
    public StockReport stock() {
        List<Map<String, Object>> rows = repository.stockReport();
        boolean consistent = rows.stream().allMatch(r -> number(r, "initial_stock")
                == number(r, "available") + number(r, "reserved") + number(r, "confirmed"));
        return new StockReport(rows, consistent);
    }

    @GetMapping("/reservations")
    public List<Map<String, Object>> reservations() {
        return repository.reservationStates();
    }

    @PostMapping("/reset")
    @Transactional
    public StockReport reset() {
        repository.reset();
        messagingStore.reset();
        return stock();
    }

    private static long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}
