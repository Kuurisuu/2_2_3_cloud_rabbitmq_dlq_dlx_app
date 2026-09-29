package com.example;

import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String ORDERS_EXCHANGE = "orders.exchange";
    public static final String ORDERS_QUEUE = "orders.queue";
    public static final String ORDERS_ROUTING_KEY = "order.created";

    public static final String DLX_EXCHANGE = "orders.dlx";
    public static final String DLQ_QUEUE = "orders.dlq";
    public static final String DLQ_MONITOR_QUEUE = "orders.dlq.monitor";
    public static final String DLX_ROUTING_KEY = "order.dead";

    public static final String FORCE_FAIL_HEADER = "x-force-fail";

    public static final int ORDERS_TTL_MS = 30_000;
    public static final int DLQ_TTL_MS = 86_400_000;
    public static final int ORDERS_MAX_LENGTH = 1000;

    @Bean
    public DirectExchange ordersExchange() {
        return new DirectExchange(ORDERS_EXCHANGE, true, false);
    }

    /**
     * Cola principal. Un mensaje termina en el DLX si:
     * se rechaza sin requeue, pasa 30 s sin consumirse, o la cola supera 1000 mensajes.
     */
    @Bean
    public Queue ordersQueue() {
        return QueueBuilder.durable(ORDERS_QUEUE)
                .withArgument("x-message-ttl", ORDERS_TTL_MS)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLX_ROUTING_KEY)
                .withArgument("x-max-length", ORDERS_MAX_LENGTH)
                .build();
    }

    @Bean
    public Binding ordersBinding(Queue ordersQueue, DirectExchange ordersExchange) {
        return BindingBuilder.bind(ordersQueue).to(ordersExchange).with(ORDERS_ROUTING_KEY);
    }

    /** Fanout: cada mensaje muerto llega a todas las colas enlazadas (auditoria y monitor). */
    @Bean
    public FanoutExchange deadLetterExchange() {
        return new FanoutExchange(DLX_EXCHANGE, true, false);
    }

    /** DLQ de auditoria. Nadie la consume: los mensajes quedan 24 h para analisis. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ_QUEUE)
                .withArgument("x-message-ttl", DLQ_TTL_MS)
                .build();
    }

    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, FanoutExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange);
    }

    /** Copia de cada mensaje muerto que consume la app para generar alertas en vivo. */
    @Bean
    public Queue deadLetterMonitorQueue() {
        return QueueBuilder.nonDurable(DLQ_MONITOR_QUEUE).autoDelete().build();
    }

    @Bean
    public Binding deadLetterMonitorBinding(Queue deadLetterMonitorQueue, FanoutExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterMonitorQueue).to(deadLetterExchange);
    }

    /**
     * Se ejecuta al agotar los reintentos. rejectManual = true hace que el contenedor
     * haga basicNack(requeue=false) aun en modo de ack manual, y el mensaje viaja al DLX.
     */
    @Bean
    public MessageRecoverer messageRecoverer() {
        return (message, cause) -> {
            throw new AmqpRejectAndDontRequeueException("Reintentos agotados", true, cause);
        };
    }
}
