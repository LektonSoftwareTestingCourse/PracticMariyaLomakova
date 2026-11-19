package com.processing.transactionlogger.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.support.converter.SimpleMessageConverter;

import java.util.List;

/**
 * Настройка топологии RabbitMQ для Transaction Logger.
 *
 * Повторяет настройки Switch: объявляет те же обменник, очередь
 * и привязки для идемпотентной настройки топологии.
 */
@Configuration
public class RabbitMQConfig {

    /** Принимает сериализуемый DTO транзакции, который отправляет Switch. */
    @Bean
    public SimpleMessageConverter transactionMessageConverter() {
        SimpleMessageConverter converter = new SimpleMessageConverter();
        converter.setAllowedListPatterns(List.of(
                "com.processing.common.dto.transactionlogger.*", "java.util.UUID",
                "java.math.*", "java.time.*", "java.lang.*"));
        return converter;
    }

    public static final String EXCHANGE = "smp.transactions";
    public static final String ROUTING_KEY = "transaction.log";
    public static final String QUEUE = "transaction-log";

    public static final String DLX_EXCHANGE = "smp.transactions.dlx";
    public static final String DLQ_QUEUE = "transaction-log-dlq";
    public static final String DLQ_ROUTING_KEY = "transaction-log";

    @Bean
    public TopicExchange smpTransactionsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    /**
     * Основная сохраняемая очередь с настройкой обработки недоставленных сообщений.
     *
     * После трёх попыток доставки (x-max-delivery) сообщение
     * отправляется в DLX {@value #DLX_EXCHANGE}. Объявление совпадает
     * с настройкой Switch для идемпотентного создания топологии.
     */
    @Bean
    public Queue transactionLogQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", DLQ_ROUTING_KEY)
                .withArgument("x-max-delivery", 3)
                .build();
    }

    @Bean
    public Binding transactionLogBinding(
            TopicExchange smpTransactionsExchange,
            Queue transactionLogQueue) {
        return BindingBuilder.bind(transactionLogQueue)
                .to(smpTransactionsExchange)
                .with(ROUTING_KEY);
    }
}
