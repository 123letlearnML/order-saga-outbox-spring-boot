package com.duong.saga.payment;

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
public class PaymentAdminController {

    /** @param consistent initialTotal = walletTotal + chargedTotal: không đồng nào tự sinh ra hay biến mất */
    public record MoneyReport(long initialTotal, long walletTotal, long chargedTotal, boolean consistent) {
    }

    private final PaymentRepository repository;
    private final MessagingStore messagingStore;

    public PaymentAdminController(PaymentRepository repository, MessagingStore messagingStore) {
        this.repository = repository;
        this.messagingStore = messagingStore;
    }

    @GetMapping("/money")
    @Transactional(readOnly = true)
    public MoneyReport money() {
        Map<String, Object> row = repository.moneyReport();
        long initial = ((Number) row.get("initialTotal")).longValue();
        long wallets = ((Number) row.get("walletTotal")).longValue();
        long charged = ((Number) row.get("chargedTotal")).longValue();
        return new MoneyReport(initial, wallets, charged, initial == wallets + charged);
    }

    @GetMapping("/payments")
    public List<Map<String, Object>> payments() {
        return repository.paymentStates();
    }

    @PostMapping("/reset")
    @Transactional
    public MoneyReport reset() {
        repository.reset();
        messagingStore.reset();
        return money();
    }
}
