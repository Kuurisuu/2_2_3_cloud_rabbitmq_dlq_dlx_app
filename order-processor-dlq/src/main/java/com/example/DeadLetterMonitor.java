package com.example;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

/**
 * Consume la copia de monitoreo del DLX (no la DLQ de auditoria) para marcar
 * la orden como fallida y exponer el motivo al frontend.
 */
@Component
public class DeadLetterMonitor {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterMonitor.class);

    private final OrderTracker tracker;

    public DeadLetterMonitor(OrderTracker tracker) {
        this.tracker = tracker;
    }

    @RabbitListener(queues = RabbitMQConfig.DLQ_MONITOR_QUEUE)
    public void onDeadLetter(Message message, Channel channel) throws IOException {
        String orderId = OrderTracker.orderIdOf(message);
        Object reasonHeader = message.getMessageProperties().getHeader("x-first-death-reason");
        String reason = reasonHeader != null ? reasonHeader.toString() : "unknown";

        tracker.markDeadLettered(orderId, OrderTracker.bodyOf(message), reason);
        log.warn("[DLQ] {} en cuarentena. Motivo: {}", orderId, reason);

        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    }
}
