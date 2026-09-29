package com.example;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;

import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

/**
 * Estado en memoria de cada orden para que el frontend vea el ciclo de vida real.
 * Se pierde al reiniciar la app; RabbitMQ sigue siendo la fuente de verdad de los mensajes.
 */
@Component
public class OrderTracker {

    private static final int MAX_TRACKED = 200;

    private final LinkedHashMap<String, OrderView> orders = new LinkedHashMap<>();
    private long sent;
    private long processed;
    private long deadLettered;

    public record Stats(long sent, long processed, long inFlight, long deadLettered) {
    }

    /** Usa el messageId de AMQP. Si falta (mensaje publicado a mano), deriva uno estable del cuerpo. */
    public static String orderIdOf(Message message) {
        String id = message.getMessageProperties().getMessageId();
        if (id != null && !id.isBlank()) {
            return id;
        }
        return "MSG-" + Integer.toHexString(Arrays.hashCode(message.getBody())).toUpperCase();
    }

    public static String bodyOf(Message message) {
        return new String(message.getBody(), StandardCharsets.UTF_8);
    }

    public synchronized void registerSent(String id, String customer, String message) {
        orders.put(id, OrderView.sent(id, customer, message));
        sent++;
        evictOldest();
    }

    public synchronized void forget(String id) {
        if (orders.remove(id) != null) {
            sent--;
        }
    }

    public synchronized int startAttempt(String id, String message) {
        OrderView next = getOrCreate(id, message).startAttempt();
        orders.put(id, next);
        return next.attempts();
    }

    public synchronized void markAttemptFailed(String id, String error, boolean willRetry) {
        OrderView current = orders.get(id);
        if (current != null) {
            OrderStatus next = willRetry ? OrderStatus.REINTENTANDO : current.status();
            orders.put(id, current.attemptFailed(error, next));
        }
    }

    public synchronized void markProcessed(String id) {
        OrderView current = orders.get(id);
        if (current != null && current.status() != OrderStatus.PROCESADA) {
            orders.put(id, current.processed());
            processed++;
        }
    }

    public synchronized void markDeadLettered(String id, String message, String reason) {
        OrderView current = getOrCreate(id, message);
        if (current.status() != OrderStatus.EN_DLQ) {
            orders.put(id, current.deadLettered(reason));
            deadLettered++;
        }
    }

    public synchronized List<OrderView> newestFirst() {
        List<OrderView> list = new ArrayList<>(orders.values());
        Collections.reverse(list);
        return list;
    }

    public synchronized Stats stats() {
        long inFlight = Math.max(0, sent - processed - deadLettered);
        return new Stats(sent, processed, inFlight, deadLettered);
    }

    public synchronized void clear() {
        orders.clear();
        sent = 0;
        processed = 0;
        deadLettered = 0;
    }

    private OrderView getOrCreate(String id, String message) {
        OrderView current = orders.get(id);
        if (current == null) {
            current = OrderView.sent(id, "Externo", message);
            orders.put(id, current);
            sent++;
            evictOldest();
        }
        return current;
    }

    private void evictOldest() {
        Iterator<String> it = orders.keySet().iterator();
        while (orders.size() > MAX_TRACKED && it.hasNext()) {
            it.next();
            it.remove();
        }
    }
}
