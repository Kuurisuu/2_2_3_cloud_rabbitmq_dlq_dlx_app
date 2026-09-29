package com.example;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.rabbitmq.client.Channel;

@Service
public class OrderConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderConsumer.class);

    private final OrderTracker tracker;
    private final double failureRate;
    private final long processingTimeMs;
    private final int maxAttempts;

    public OrderConsumer(OrderTracker tracker,
                         @Value("${app.failure-rate}") double failureRate,
                         @Value("${app.processing-time-ms}") long processingTimeMs,
                         @Value("${spring.rabbitmq.listener.simple.retry.max-attempts}") int maxAttempts) {
        this.tracker = tracker;
        this.failureRate = failureRate;
        this.processingTimeMs = processingTimeMs;
        this.maxAttempts = maxAttempts;
    }

    /**
     * Si lanza una excepcion, Spring Retry vuelve a invocar este metodo con backoff.
     * Tras el ultimo intento, el MessageRecoverer rechaza el mensaje y RabbitMQ lo manda al DLX.
     */
    @RabbitListener(queues = RabbitMQConfig.ORDERS_QUEUE)
    public void processOrder(Message message, Channel channel) throws IOException, InterruptedException {
        String orderId = OrderTracker.orderIdOf(message);
        String body = OrderTracker.bodyOf(message);
        int attempt = tracker.startAttempt(orderId, body);

        log.info("[PROCESADOR] {} intento {}/{}: {}", orderId, attempt, maxAttempts, body);
        Thread.sleep(processingTimeMs);

        if (shouldFail(message)) {
            String error = "Error simulado en el intento " + attempt;
            tracker.markAttemptFailed(orderId, error, attempt < maxAttempts);
            log.warn("[ERROR] {} {}", orderId, error);
            throw new IllegalStateException(error);
        }

        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
        tracker.markProcessed(orderId);
        log.info("[EXITO] {} procesada en el intento {}", orderId, attempt);
    }

    private boolean shouldFail(Message message) {
        Object forceFail = message.getMessageProperties().getHeader(RabbitMQConfig.FORCE_FAIL_HEADER);
        return Boolean.parseBoolean(String.valueOf(forceFail))
                || ThreadLocalRandom.current().nextDouble() < failureRate;
    }
}
