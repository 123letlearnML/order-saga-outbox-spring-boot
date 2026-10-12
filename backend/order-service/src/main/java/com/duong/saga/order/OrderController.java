package com.duong.saga.order;

import com.duong.saga.order.OrderRepository.Item;
import com.duong.saga.order.OrderRepository.Order;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@RequestMapping("/api/orders")
public class OrderController {

    public record ItemRequest(@NotBlank @Size(max = 20) String sku, @Min(1) @Max(10) int qty) {
    }

    public record PlaceOrderRequest(@NotNull @Positive Long customerId,
                                    @NotEmpty @Size(max = 5) List<@Valid ItemRequest> items) {
    }

    public record OrderView(long id, long customerId, String status, Long totalAmount, String failureReason,
                            List<Item> items, int emailsSent) {
    }

    private final OrderService orderService;
    private final OrderRepository orders;

    public OrderController(OrderService orderService, OrderRepository orders) {
        this.orderService = orderService;
        this.orders = orders;
    }

    /** 202 Accepted: đơn đã được ghi nhận, kết quả cuối cùng (CONFIRMED / REJECTED / CANCELLED) xem qua GET. */
    @PostMapping
    public ResponseEntity<OrderView> place(@RequestHeader("Idempotency-Key") @NotBlank @Size(max = 64) String key,
                                           @Valid @RequestBody PlaceOrderRequest request) {
        List<Item> items = request.items().stream().map(i -> new Item(i.sku(), i.qty())).toList();
        OrderService.PlaceOrderResult result = orderService.place(key, request.customerId(), items);
        OrderView view = view(result.order());
        if (result.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(view);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(view);
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderView> get(@PathVariable long id) {
        return orders.findById(id).map(o -> ResponseEntity.ok(view(o))).orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<OrderView> recent(@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
        return orders.recent(limit).stream().map(this::view).toList();
    }

    private OrderView view(Order o) {
        return new OrderView(o.id(), o.customerId(), o.status(), o.totalAmount(), o.failureReason(),
                orders.items(o.id()), orders.emailCount(o.id()));
    }
}
