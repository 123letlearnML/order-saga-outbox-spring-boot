package com.duong.saga.messaging;

import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Quan sát hàng đợi của service này. Hệ thống thật phải bảo vệ bằng phân quyền ADMIN. */
@RestController
@RequestMapping("/api/admin/messaging")
public class MessagingAdminController {

    public record MessagingStats(String service,
                                 SagaMode mode,
                                 long outboxPending,
                                 long outboxSent,
                                 long outboxDead,
                                 long inboxProcessed,
                                 long duplicatesSuppressed,
                                 long naiveSendFailures) {
    }

    private final SagaProperties properties;
    private final MessagingStore store;

    public MessagingAdminController(SagaProperties properties, MessagingStore store) {
        this.properties = properties;
        this.store = store;
    }

    @GetMapping
    public MessagingStats stats() {
        Map<String, Long> outbox = store.outboxCountsByStatus();
        return new MessagingStats(properties.service(), properties.mode(),
                outbox.get("PENDING"), outbox.get("SENT"), outbox.get("DEAD"),
                store.inboxCount(),
                store.counter(MessagingStore.DUPLICATES_SUPPRESSED),
                store.counter(MessagingStore.NAIVE_SEND_FAILURES));
    }

    /** Dead letter queue: message đã bỏ cuộc, kèm lỗi cuối cùng. */
    @GetMapping("/dead")
    public List<Map<String, Object>> deadLetters() {
        return store.deadLetters(100);
    }

    @PostMapping("/dead/redrive-all")
    public Map<String, Integer> redriveAll() {
        return Map.of("redriven", store.redriveAll());
    }

    @PostMapping("/dead/{id}/redrive")
    public ResponseEntity<Void> redrive(@PathVariable long id) {
        return store.redrive(id) == 1 ? ResponseEntity.accepted().build() : ResponseEntity.notFound().build();
    }
}
