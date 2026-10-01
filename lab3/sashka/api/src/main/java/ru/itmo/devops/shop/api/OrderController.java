package ru.itmo.devops.shop.api;

import java.net.URI;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import static net.logstash.logback.argument.StructuredArguments.keyValue;

@RestController
public class OrderController {
    private static final Logger LOGGER = LoggerFactory.getLogger(OrderController.class);

    private final OrderRepository repository;

    public OrderController(OrderRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/order")
    public ResponseEntity<Order> create(@Valid @RequestBody OrderRequest request) {
        Order order = repository.create(request);
        TraceLog.info(
                LOGGER,
                "Order created {}, {}, {}",
                keyValue("order_id", order.id()),
                keyValue("item", order.item()),
                keyValue("quantity", order.quantity())
        );
        return ResponseEntity.created(URI.create("/orders/" + order.id())).body(order);
    }

    @GetMapping("/orders")
    public List<Order> findAll() {
        return repository.findAll();
    }
}
