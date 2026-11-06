package com.processing.gateway.validation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.gateway.metrics.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionValidationFilterTest {

    @Mock
    private GatewayFilterChain chain;
    @Mock
    private GatewayMetrics metrics;

    private ObjectMapper objectMapper;
    private TransactionValidationFilter filter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        filter = new TransactionValidationFilter(objectMapper, new TransactionRequestValidator(), metrics);
    }

    @Test
    void shouldForwardOriginalBodyAndContentLengthWhenTransactionIsValid() throws Exception {
        //Arrange
        String body = objectMapper.writeValueAsString(requestWithAmount(new BigDecimal("1000")));
        MockServerWebExchange exchange = transactionExchange(body);
        when(chain.filter(any(ServerWebExchange.class))).thenReturn(Mono.empty());
        ArgumentCaptor<ServerWebExchange> forwarded = ArgumentCaptor.forClass(ServerWebExchange.class);

        //Act
        Mono<Void> result = filter.filter(exchange, chain);

        //Assert
        StepVerifier.create(result).verifyComplete();
        verify(chain).filter(forwarded.capture());
        assertThat(forwarded.getValue().getRequest().getHeaders().getContentLength())
                .isEqualTo(body.getBytes(StandardCharsets.UTF_8).length);
        assertThat(readRequestBody(forwarded.getValue())).isEqualTo(body);
        assertThat(readRequestBody(forwarded.getValue())).isEqualTo(body);
        verifyNoInteractions(metrics);
    }

    @ParameterizedTest(name = "invalidJson={0}")
    @ValueSource(strings = {"", "{", "[]"})
    void shouldRejectWithoutForwardingWhenJsonIsMalformed(String body) throws Exception {
        //Arrange
        MockServerWebExchange exchange = transactionExchange(body);

        //Act
        Mono<Void> result = filter.filter(exchange, chain);

        //Assert
        StepVerifier.create(result).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        JsonNode error = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(error.path("error").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.path("message").asText()).isEqualTo("Request body must be valid JSON");
        assertThat(error.path("serviceName").asText()).isEqualTo("gateway");
        verify(metrics).recordValidationRejected("invalid_json");
        verifyNoInteractions(chain);
    }

    @Test
    void shouldReturnValidationReasonWhenTransactionIsInvalid() throws Exception {
        //Arrange
        String body = objectMapper.writeValueAsString(requestWithAmount(BigDecimal.ZERO));
        MockServerWebExchange exchange = transactionExchange(body);

        //Act
        Mono<Void> result = filter.filter(exchange, chain);

        //Assert
        StepVerifier.create(result).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode error = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(error.path("error").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(error.path("message").asText()).isEqualTo("Field 'amount' must be > 0");
        verify(metrics).recordValidationRejected("invalid_request");
        verifyNoInteractions(chain);
    }

    @Test
    void shouldRejectMissingTransactionWhenJsonBodyIsNull() throws Exception {
        //Arrange
        MockServerWebExchange exchange = transactionExchange("null");

        //Act
        Mono<Void> result = filter.filter(exchange, chain);

        //Assert
        StepVerifier.create(result).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode error = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(error.path("message").asText()).isEqualTo("Request body is required");
        verify(metrics).recordValidationRejected("invalid_request");
        verifyNoInteractions(chain);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"GET, /api/transactions", "POST, /api/cards", "POST, /api/transactions/search"})
    void shouldForwardWithoutTransactionValidationWhenRequestTargetsAnotherOperation(String method, String path) {
        //Arrange
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.valueOf(method), path).build());
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        //Act
        Mono<Void> result = filter.filter(exchange, chain);

        //Assert
        StepVerifier.create(result).verifyComplete();
        verify(chain).filter(exchange);
        verifyNoInteractions(metrics);
    }

    private MockServerWebExchange transactionExchange(String body) {
        return MockServerWebExchange.from(
                MockServerHttpRequest.post("/api/transactions").contentType(MediaType.APPLICATION_JSON).body(body));
    }

    private String readRequestBody(ServerWebExchange exchange) {
        return DataBufferUtils.join(exchange.getRequest().getBody()).map(buffer -> {
            String body = buffer.toString(StandardCharsets.UTF_8);
            DataBufferUtils.release(buffer);
            return body;
        }).block();
    }

    private AuthorizationRequest requestWithAmount(BigDecimal amount) {
        return new AuthorizationRequest(
                "0100", "000001", "4000003458730237", "000000", amount, "643",
                Instant.parse("2026-01-31T23:59:59Z"), "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", null);
    }
}
