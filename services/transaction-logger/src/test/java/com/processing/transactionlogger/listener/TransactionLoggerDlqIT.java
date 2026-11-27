package com.processing.transactionlogger.listener;

import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.transactionlogger.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static com.processing.transactionlogger.config.RabbitMQConfig.DLX_EXCHANGE;
import static com.processing.transactionlogger.config.RabbitMQConfig.DLQ_QUEUE;
import static com.processing.transactionlogger.config.RabbitMQConfig.DLQ_ROUTING_KEY;
import static com.processing.transactionlogger.config.RabbitMQConfig.EXCHANGE;
import static com.processing.transactionlogger.config.RabbitMQConfig.QUEUE;
import static com.processing.transactionlogger.config.RabbitMQConfig.ROUTING_KEY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TransactionLoggerDlqIT.DeadLetterTopology.class)
@Testcontainers
class TransactionLoggerDlqIT {

    private static final String TRACE_QUEUE = "transaction-log-delivery-trace";
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private RabbitTemplate publisher;

    @Autowired
    private AmqpAdmin broker;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionRepository transactions;

    @BeforeEach
    void cleanDatabaseAndBroker() throws Exception {
        jdbc.execute("TRUNCATE TABLE transactions RESTART IDENTITY CASCADE");
        broker.declareQueue(new Queue(TRACE_QUEUE, false, false, true));
        broker.declareBinding(new Binding(TRACE_QUEUE, Binding.DestinationType.QUEUE,
                "amq.rabbitmq.trace", "deliver." + QUEUE, null));
        assertThat(RABBIT.execInContainer("rabbitmqctl", "trace_on", "-p", "/").getExitCode()).isZero();
        broker.purgeQueue(QUEUE);
        broker.purgeQueue(DLQ_QUEUE);
        broker.purgeQueue(TRACE_QUEUE);
        await("logger subscribed to " + QUEUE).atMost(ASYNC_TIMEOUT).pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> assertThat(broker.getQueueInfo(QUEUE).getConsumerCount()).isPositive());
    }

    @Test
    void rejectsPoisonOnFirstDeliveryAndProcessesNextValidMessage() {
        String messageId = UUID.randomUUID().toString();
        byte[] invalidJson = "{\"broken\":".getBytes(StandardCharsets.UTF_8);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(messageId);

        publisher.send(EXCHANGE, ROUTING_KEY, new Message(invalidJson, properties));

        await("poison message in " + DLQ_QUEUE).atMost(ASYNC_TIMEOUT).pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> assertThat(broker.getQueueInfo(DLQ_QUEUE).getMessageCount()).isEqualTo(1));
        Message deadLetter = publisher.receive(DLQ_QUEUE);
        assertThat(deadLetter).isNotNull();
        assertThat(deadLetter.getBody()).isEqualTo(invalidJson);
        assertThat(deadLetter.getMessageProperties().getMessageId()).isEqualTo(messageId);
        assertThat(deadLetter.getMessageProperties().getXDeathHeader()).singleElement().satisfies(death -> {
            assertThat(death.get("reason").toString()).isEqualTo("rejected");
            assertThat(death.get("queue").toString()).isEqualTo(QUEUE);
        });
        assertThat(broker.getQueueInfo(QUEUE).getMessageCount()).isZero();

        // Трассировка брокера учитывает повторные доставки после возврата в очередь. В x-death их не видно.
        await("one broker delivery of the poison message").atMost(ASYNC_TIMEOUT).pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> assertThat(broker.getQueueInfo(TRACE_QUEUE).getMessageCount()).isEqualTo(1));
        Message delivery = publisher.receive(TRACE_QUEUE);
        assertThat(delivery).isNotNull();
        assertThat(delivery.getBody()).isEqualTo(invalidJson);
        assertThat(publisher.receive(TRACE_QUEUE)).isNull();
        assertThat(transactions.count()).isZero();

        TransactionRequest valid = approvedTransaction();
        publisher.convertAndSend(EXCHANGE, ROUTING_KEY, valid);

        await("logger persisted follow-up transaction " + valid.id()).atMost(ASYNC_TIMEOUT).pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> assertThat(transactions.findById(valid.id())).hasValueSatisfying(stored -> {
                    assertThat(stored.getRrn()).isEqualTo(valid.rrn());
                    assertThat(stored.getStan()).isEqualTo(valid.stan());
                    assertThat(stored.getStatus()).isEqualTo(TransactionStatus.APPROVED);
                }));
        assertThat(broker.getQueueInfo(DLQ_QUEUE).getMessageCount()).isZero();
    }

    private static TransactionRequest approvedTransaction() {
        String stan = String.format(Locale.ROOT, "%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        Instant now = Instant.now();
        return new TransactionRequest(UUID.randomUUID(), "0100", stan, "000000" + stan, "4000000000" + stan,
                "000000", BigDecimal.valueOf(500), "643", "TERM0001", "POS", "MERCH0000000001", "5411",
                "ACQ001", "ISS001", BigDecimal.valueOf(5), TransactionStatus.APPROVED, null, "ABC123", 1, now, now);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class DeadLetterTopology {

        @Bean
        DirectExchange deadLetterExchange() {
            return new DirectExchange(DLX_EXCHANGE, true, false);
        }

        @Bean
        Queue deadLetterQueue() {
            return QueueBuilder.durable(DLQ_QUEUE).ttl(60_000).build();
        }

        @Bean
        Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
            return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(DLQ_ROUTING_KEY);
        }
    }
}
