package com.processing.gateway.ratelimit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processing.gateway.metrics.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionRateLimitFilterTest {

    private static final String CLIENT_IP = "192.0.2.1";
    private static final String RATE_LIMIT_KEY = "POST /api/transactions:" + CLIENT_IP;

    @Mock
    private InMemoryRateLimiter rateLimiter;
    @Mock
    private ClientIpResolver clientIpResolver;
    @Mock
    private GatewayFilterChain chain;
    @Mock
    private GatewayMetrics metrics;

    private ObjectMapper objectMapper;
    private TransactionRateLimitFilter filter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        filter = new TransactionRateLimitFilter(rateLimiter, clientIpResolver, objectMapper, metrics);
    }

    @Test
    void shouldForwardTransactionWhenClientHasAvailableToken() {
        //Arrange
        MockServerWebExchange exchange = transactionExchange();
        when(clientIpResolver.resolve(exchange)).thenReturn(CLIENT_IP);
        when(rateLimiter.allowRequest(RATE_LIMIT_KEY)).thenReturn(true);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        //Act
        Mono<Void> result = filter.filter(exchange, chain);

        //Assert
        StepVerifier.create(result).verifyComplete();
        verify(chain).filter(exchange);
        verifyNoInteractions(metrics);
    }

    @Test
    void shouldReturnTooManyRequestsWithRetryAfterWhenClientLimitIsExceeded() throws Exception {
        //Arrange
        MockServerWebExchange exchange = transactionExchange();
        when(clientIpResolver.resolve(exchange)).thenReturn(CLIENT_IP);
        when(rateLimiter.allowRequest(RATE_LIMIT_KEY)).thenReturn(false);

        //Act
        Mono<Void> result = filter.filter(exchange, chain);

        //Assert
        StepVerifier.create(result).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        JsonNode error = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(error.path("error").asText()).isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(error.path("message").asText()).isEqualTo("Too many requests. Try again later.");
        assertThat(error.path("retryAfterMs").asInt()).isEqualTo(1000);
        verify(metrics).recordRateLimitRejected();
        verifyNoInteractions(chain);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"GET, /api/transactions", "POST, /api/cards", "POST, /api/transactions/search"})
    void shouldForwardWithoutTransactionLimitWhenRequestTargetsAnotherOperation(String method, String path) {
        //Arrange
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(HttpMethod.valueOf(method), path).build());
        when(clientIpResolver.resolve(exchange)).thenReturn(CLIENT_IP);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        //Act
        Mono<Void> result = filter.filter(exchange, chain);

        //Assert
        StepVerifier.create(result).verifyComplete();
        verify(chain).filter(exchange);
        verifyNoInteractions(metrics);
    }

    private MockServerWebExchange transactionExchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post("/api/transactions").build());
    }
}
