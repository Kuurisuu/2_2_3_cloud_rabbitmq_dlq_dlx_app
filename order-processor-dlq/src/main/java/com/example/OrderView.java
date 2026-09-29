package com.example;

import java.time.Instant;

public record OrderView(
        String id,
        String customer,
        String message,
        OrderStatus status,
        int attempts,
        String lastError,
        String deadLetterReason,
        Instant createdAt,
        Instant updatedAt) {

    static OrderView sent(String id, String customer, String message) {
        Instant now = Instant.now();
        return new OrderView(id, customer, message, OrderStatus.ENVIADA, 0, null, null, now, now);
    }

    OrderView startAttempt() {
        return new OrderView(id, customer, message, OrderStatus.PROCESANDO, attempts + 1,
                lastError, null, createdAt, Instant.now());
    }

    OrderView attemptFailed(String error, OrderStatus nextStatus) {
        return new OrderView(id, customer, message, nextStatus, attempts,
                error, null, createdAt, Instant.now());
    }

    OrderView processed() {
        return new OrderView(id, customer, message, OrderStatus.PROCESADA, attempts,
                lastError, null, createdAt, Instant.now());
    }

    OrderView deadLettered(String reason) {
        return new OrderView(id, customer, message, OrderStatus.EN_DLQ, attempts,
                lastError, reason, createdAt, Instant.now());
    }
}
