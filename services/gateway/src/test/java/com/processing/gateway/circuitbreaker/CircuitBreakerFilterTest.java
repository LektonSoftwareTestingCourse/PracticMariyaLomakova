package com.processing.gateway.circuitbreaker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.processing.gateway.downstream.DownstreamServiceResolver;
import com.processing.gateway.metrics.GatewayMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.client.ResourceAccessException;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CircuitBreakerFilterTest {

    private static final String PATH = "/api/transactions";
    private static final String SERVICE = "switch";
    private static final Duration OPEN_DURATION = Duration.ofSeconds(10);
    private static final Instant OPENED_AT = Instant.parse("2026-01-31T12:00:00Z");
    private static final Instant PROBE_AT = OPENED_AT.plus(OPEN_DURATION);

    @Mock
    private DownstreamServiceResolver serviceResolver;
    @Mock
    private Clock clock;
    @Mock
    private GatewayFilterChain chain;
    @Mock
    private GatewayMetrics metrics;

    private ObjectMapper objectMapper;
    private InMemoryCircuitBreaker circuitBreaker;
    private CircuitBreakerFilter filter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        circuitBreaker = InMemoryCircuitBreaker.forTesting(OPEN_DURATION, 1, clock);
        filter = new CircuitBreakerFilter(objectMapper, serviceResolver, circuitBreaker, metrics, OPEN_DURATION);
    }

    @Test
    void shouldForwardRequestWhenNoDownstreamServiceMatches() {
        //Arrange
        MockServerWebExchange exchange = transactionExchange();
        when(serviceResolver.resolve(PATH)).thenReturn(Optional.empty());
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        //Act
        Mono<Void> result = filter.filter(exchange, chain);

        //Assert
        StepVerifier.create(result).verifyComplete();
        verify(chain).filter(exchange);
        verifyNoInteractions(metrics);
    }

    @Test
    void shouldReturnServiceUnavailableWithoutForwardingWhenCircuitIsOpen() throws Exception {
        //Arrange
        MockServerWebExchange exchange = transactionExchange();
        stubDownstreamRoute();
        openCircuitThenAdvanceTo(PROBE_AT.minusMillis(1));

        //Act
        Mono<Void> result = filter.filter(exchange, chain);

        //Assert
        StepVerifier.create(result).verifyComplete();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(exchange.getResponse().getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(exchange.getResponse().getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo(Long.toString(OPEN_DURATION.toSeconds()));
        JsonNode error = objectMapper.readTree(exchange.getResponse().getBodyAsString().block());
        assertThat(error.path("error").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(error.path("serviceName").asText()).isEqualTo(SERVICE);
        assertThat(error.path("message").asText()).isEqualTo("switch service is temporarily unavailable");
        verify(metrics).recordCircuitOpen(SERVICE);
        verifyNoInteractions(chain);
    }

    @ParameterizedTest(name = "status={0}")
    @ValueSource(ints = {200, 201, 400, 401, 429})
    void shouldCloseCircuitWhenProbeCompletesWithoutServerFailure(int status) {
        //Arrange
        MockServerWebExchange exchange = transactionExchange();
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(status));
        stubDownstreamRoute();
        openCircuitThenAdvanceTo(PROBE_AT);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        //Act
        Mono<Void> result = filter.filter(exchange, chain);

        //Assert
        StepVerifier.create(result).verifyComplete();
        verify(chain).filter(exchange);
        assertCircuitIsClosed();
    }

    @Test
    void shouldCloseCircuitWhenProbeResponseHasNoExplicitStatus() {
        //Arrange
        MockServerWebExchange exchange = transactionExchange();
        stubDownstreamRoute();
        openCircuitThenAdvanceTo(PROBE_AT);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        //Act
        Mono<Void> result = filter.filter(exchange, chain);

        //Assert
        StepVerifier.create(result).verifyComplete();
        assertCircuitIsClosed();
    }

    @ParameterizedTest(name = "status={0}")
    @ValueSource(ints = {500, 502, 503, 504})
    void shouldReopenCircuitWhenProbeResponseHasServerErrorStatus(int status) {
        //Arrange
        MockServerWebExchange exchange = transactionExchange();
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(status));
        stubDownstreamRoute();
        openCircuitThenAdvanceTo(PROBE_AT);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        //Act
        Mono<Void> result = filter.filter(exchange, chain);

        //Assert
        StepVerifier.create(result).verifyComplete();
        assertCircuitHasReopened();
    }

    @ParameterizedTest(name = "status={0}")
    @ValueSource(ints = {404, 405})
    void shouldAllowOnlyOneMoreProbeWhenOperationIsUnmapped(int status) {
        //Arrange
        MockServerWebExchange exchange = transactionExchange();
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(status));
        stubDownstreamRoute();
        openCircuitThenAdvanceTo(PROBE_AT);
        when(chain.filter(exchange)).thenReturn(Mono.empty());

        //Act
        Mono<Void> result = filter.filter(exchange, chain);

        //Assert
        StepVerifier.create(result).verifyComplete();
        assertCircuitAllowsAnotherProbe();
    }

    @ParameterizedTest(name = "failure={0}")
    @MethodSource("connectionFailures")
    void shouldReopenCircuitAndPropagateErrorWhenProbeConnectionFails(Throwable error) {
        //Arrange
        MockServerWebExchange exchange = transactionExchange();
        stubDownstreamRoute();
        openCircuitThenAdvanceTo(PROBE_AT);
        when(chain.filter(exchange)).thenReturn(Mono.error(error));

        //Act
        Mono<Void> result = filter.filter(exchange, chain);

        //Assert
        StepVerifier.create(result)
                .expectErrorSatisfies(failure -> assertThat(failure)
                        .isExactlyInstanceOf(error.getClass()).hasMessage(error.getMessage()))
                .verify();
        assertCircuitHasReopened();
    }

    @Test
    void shouldAllowOnlyOneMoreProbeAndPropagateErrorWhenApplicationFails() {
        //Arrange
        MockServerWebExchange exchange = transactionExchange();
        var error = new IllegalArgumentException("Invalid operation");
        stubDownstreamRoute();
        openCircuitThenAdvanceTo(PROBE_AT);
        when(chain.filter(exchange)).thenReturn(Mono.error(error));

        //Act
        Mono<Void> result = filter.filter(exchange, chain);

        //Assert
        StepVerifier.create(result)
                .expectErrorSatisfies(failure -> assertThat(failure)
                        .isExactlyInstanceOf(IllegalArgumentException.class).hasMessage(error.getMessage()))
                .verify();
        assertCircuitAllowsAnotherProbe();
    }

    private static Stream<Throwable> connectionFailures() {
        return Stream.of(
                new ResourceAccessException("Timeout"),
                new ConnectException("Connection refused"),
                new SocketTimeoutException("Read timeout"),
                new IllegalStateException("Wrapped error", new ConnectException("Connection refused")));
    }

    private void stubDownstreamRoute() {
        when(serviceResolver.resolve(PATH)).thenReturn(Optional.of(SERVICE));
    }

    private void openCircuitThenAdvanceTo(Instant requestTime) {
        when(clock.instant()).thenReturn(OPENED_AT);
        circuitBreaker.recordFailure(SERVICE);
        when(clock.instant()).thenReturn(requestTime);
    }

    private void assertCircuitIsClosed() {
        assertThat(circuitBreaker.allowRequest(SERVICE)).as("first request").isTrue();
        assertThat(circuitBreaker.allowRequest(SERVICE)).as("second request").isTrue();
    }

    private void assertCircuitHasReopened() {
        Instant nextProbeAt = PROBE_AT.plus(OPEN_DURATION);
        when(clock.instant()).thenReturn(nextProbeAt.minusMillis(1));
        assertThat(circuitBreaker.allowRequest(SERVICE)).as("before the new open interval ends").isFalse();

        when(clock.instant()).thenReturn(nextProbeAt);
        assertThat(circuitBreaker.allowRequest(SERVICE)).as("probe after the new open interval").isTrue();
    }

    private void assertCircuitAllowsAnotherProbe() {
        assertThat(circuitBreaker.allowRequest(SERVICE)).as("available probe").isTrue();
        assertThat(circuitBreaker.allowRequest(SERVICE)).as("second probe while the first is in progress").isFalse();
    }

    private MockServerWebExchange transactionExchange() {
        return MockServerWebExchange.from(MockServerHttpRequest.post(PATH).build());
    }
}
