package com.processing.service;

import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.math.BigDecimal;
import java.net.ConnectException;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LoggerClientTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    private LoggerClient loggerClient;

    @BeforeEach
    void setUp() {
        loggerClient = new LoggerClient(rabbitTemplate);
    }

    @Test
    void shouldPublishToConfiguredExchangeAndRoutingKeyWhenLoggingTransaction() {
        //Arrange
        TransactionRequest transaction = approvedTransaction();

        //Act
        boolean published = loggerClient.log(transaction);

        //Assert
        assertThat(published).isTrue();
        verify(rabbitTemplate).convertAndSend("smp.transactions", "transaction.log", transaction);
    }

    @ParameterizedTest(name = "brokerFailure={0}")
    @MethodSource("brokerFailures")
    void shouldReportPublicationFailureWhenBrokerIsUnavailable(AmqpException failure) {
        //Arrange
        TransactionRequest transaction = approvedTransaction();
        doThrow(failure).when(rabbitTemplate).convertAndSend("smp.transactions", "transaction.log", transaction);

        //Act
        boolean published = loggerClient.log(transaction);

        //Assert
        assertThat(published).isFalse();
        verify(rabbitTemplate).convertAndSend("smp.transactions", "transaction.log", transaction);
    }

    private static Stream<AmqpException> brokerFailures() {
        return Stream.of(new AmqpException("Publish failed"), new AmqpConnectException(new ConnectException("Refused")));
    }

    private TransactionRequest approvedTransaction() {
        Instant transactionTime = Instant.parse("2026-01-31T23:59:59Z");
        return new TransactionRequest(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), "0100", "000001", "603100001000",
                "4000003458730237", "000000", new BigDecimal("1000"), "643", "TERM0001", "POS", "MERCH0000000002",
                "5045", "ACQ002", "ISS001", new BigDecimal("25"), TransactionStatus.APPROVED, null, "AUTH01", 12,
                transactionTime, transactionTime);
    }
}
