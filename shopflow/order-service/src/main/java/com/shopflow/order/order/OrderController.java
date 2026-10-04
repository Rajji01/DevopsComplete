package com.shopflow.order.order;

import java.net.URI;
import java.time.Instant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.resilience4j.ratelimiter.annotation.RateLimiter;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    public record CreateOrderRequest(@NotBlank @Size(max = 64) String sku,
                                     @Min(1) @Max(100) int quantity) {
    }

    public record OrderResponse(Long id, String orderRef, String sku, int quantity,
                                OrderStatus status, String failureReason, Instant createdAt) {

        static OrderResponse from(Order o) {
            return new OrderResponse(o.getId(), o.getOrderRef(), o.getSku(), o.getQuantity(),
                    o.getStatus(), o.getFailureReason(), o.getCreatedAt());
        }
    }

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    /**
     * 201 + Location for a new order, 200 when the Idempotency-Key was seen before.
     * The body's status says whether stock was reserved (CONFIRMED) or not (REJECTED).
     */
    @PostMapping
    @RateLimiter(name = "orders") // protects the DB and inventory from a traffic burst; excess -> 429
    public ResponseEntity<OrderResponse> create(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false) @Size(max = 100) String idempotencyKey) {
        var placed = orderService.placeOrder(request.sku(), request.quantity(), idempotencyKey);
        var body = OrderResponse.from(placed.order());
        if (!placed.created()) {
            return ResponseEntity.ok(body);
        }
        return ResponseEntity.created(URI.create("/api/v1/orders/" + body.id())).body(body);
    }

    @GetMapping("/{id}")
    public OrderResponse get(@PathVariable long id) {
        return OrderResponse.from(orderService.get(id));
    }

    @GetMapping
    public PagedModel<OrderResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        return new PagedModel<>(orderService.list(pageable).map(OrderResponse::from));
    }

    @DeleteMapping("/{id}")
    public OrderResponse cancel(@PathVariable long id) {
        return OrderResponse.from(orderService.cancel(id));
    }
}
