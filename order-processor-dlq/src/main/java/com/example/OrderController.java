package com.example;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
@CrossOrigin(origins = "${app.cors-origin}")
public class OrderController {

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_BURST = 100;

    private final RabbitTemplate rabbitTemplate;
    private final AmqpAdmin amqpAdmin;
    private final OrderTracker tracker;
    private final AtomicLong sequence = new AtomicLong(System.currentTimeMillis());
    private final Map<String, Object> config;

    public record SendOrderRequest(String orderId, String customerName, Boolean forceFail) {
    }

    public record BurstRequest(Integer count, String customerName) {
    }

    public record QueueDepth(String name, long messages, int consumers) {
    }

    public record Dashboard(List<OrderView> orders, OrderTracker.Stats stats,
                            List<QueueDepth> queues, Map<String, Object> config) {
    }

    public OrderController(RabbitTemplate rabbitTemplate, AmqpAdmin amqpAdmin, OrderTracker tracker,
                           @Value("${spring.rabbitmq.listener.simple.retry.max-attempts}") int maxAttempts,
                           @Value("${app.failure-rate}") double failureRate) {
        this.rabbitTemplate = rabbitTemplate;
        this.amqpAdmin = amqpAdmin;
        this.tracker = tracker;
        this.config = Map.of(
                "maxAttempts", maxAttempts,
                "failureRate", failureRate,
                "ordersTtlMs", RabbitMQConfig.ORDERS_TTL_MS,
                "dlqTtlMs", RabbitMQConfig.DLQ_TTL_MS);
    }

    @PostMapping("/send")
    public Map<String, String> sendOrder(@RequestBody SendOrderRequest request) {
        String orderId = hasText(request.orderId()) ? request.orderId() : nextOrderId();
        String customer = hasText(request.customerName()) ? request.customerName() : "Unknown";
        String message = publish(orderId, customer, Boolean.TRUE.equals(request.forceFail()));

        Map<String, String> response = new LinkedHashMap<>();
        response.put("status", "Orden enviada");
        response.put("orderId", orderId);
        response.put("message", message);
        return response;
    }

    /** Envia varias ordenes de golpe. Util para ver expirar mensajes por TTL. */
    @PostMapping("/burst")
    public Map<String, Object> sendBurst(@RequestBody BurstRequest request) {
        int count = Math.max(1, Math.min(MAX_BURST, request.count() == null ? 20 : request.count()));
        String customer = hasText(request.customerName()) ? request.customerName() : "Rafaga";

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String orderId = nextOrderId();
            publish(orderId, customer, false);
            ids.add(orderId);
        }
        return Map.of("status", "Rafaga enviada", "count", count, "orderIds", ids);
    }

    @GetMapping
    public Dashboard dashboard() {
        return new Dashboard(tracker.newestFirst(), tracker.stats(), queueDepths(), config);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clearHistory() {
        tracker.clear();
    }

    @GetMapping("/status")
    public Map<String, String> getStatus() {
        Map<String, String> status = new LinkedHashMap<>();
        status.put("backend", "Online");
        status.put("rabbitmq", queueDepths().isEmpty() ? "Desconectado" : "Conectado");
        status.put("timestamp", LocalDateTime.now().toString());
        return status;
    }

    @ExceptionHandler(AmqpException.class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> onBrokerError(AmqpException e) {
        return Map.of("error", "RabbitMQ no disponible", "detail", String.valueOf(e.getMessage()));
    }

    private String publish(String orderId, String customer, boolean forceFail) {
        String message = String.format("Orden ID: %s | Cliente: %s | Hora: %s",
                orderId, customer, LocalDateTime.now().format(HOUR));

        tracker.registerSent(orderId, customer, message);
        try {
            rabbitTemplate.convertAndSend(RabbitMQConfig.ORDERS_EXCHANGE, RabbitMQConfig.ORDERS_ROUTING_KEY, message, m -> {
                m.getMessageProperties().setMessageId(orderId);
                if (forceFail) {
                    m.getMessageProperties().setHeader(RabbitMQConfig.FORCE_FAIL_HEADER, true);
                }
                return m;
            });
        } catch (AmqpException e) {
            tracker.forget(orderId);
            throw e;
        }
        return message;
    }

    private List<QueueDepth> queueDepths() {
        List<QueueDepth> depths = new ArrayList<>();
        try {
            for (String name : List.of(RabbitMQConfig.ORDERS_QUEUE, RabbitMQConfig.DLQ_QUEUE)) {
                QueueInformation info = amqpAdmin.getQueueInfo(name);
                if (info != null) {
                    depths.add(new QueueDepth(name, info.getMessageCount(), info.getConsumerCount()));
                }
            }
        } catch (AmqpException e) {
            return List.of();
        }
        return depths;
    }

    private String nextOrderId() {
        return "ORD-" + sequence.incrementAndGet();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
