package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.config.SwitchProperties;
import com.processing.exception.AuthorizationException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationClientTest {

    private static final String AUTHORIZATION_URL = "http://authorization.test";
    private static final String STAN = "000001";
    private static final String PAN = "4000003458730237";
    private static final String RRN = "603100001000";
    private static final int MAX_ATTEMPTS = 3;
    private static final BigDecimal AMOUNT = new BigDecimal("1000");

    @Mock
    private RestClient restClient;
    @Mock
    private RestClient.RequestBodyUriSpec postSpec;
    @Mock
    private RestClient.RequestBodySpec bodySpec;
    @Mock
    private RestClient.ResponseSpec responseSpec;
    @Mock
    private RestClient.RequestHeadersUriSpec<?> getSpec;
    @Mock
    private RestClient.RequestHeadersSpec<?> headersSpec;

    private AuthorizationClient authorizationClient;
    private Retry retry;
    private CircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        var properties = new SwitchProperties(
                "1.0.0", Map.of("400000", "ISS001"), AUTHORIZATION_URL, null, null, null,
                new SwitchProperties.RetryProperties(MAX_ATTEMPTS, List.of(0L)), null);
        retry = Retry.of("authorization-test", RetryConfig.custom()
                .maxAttempts(MAX_ATTEMPTS)
                .intervalFunction(attempt -> 0L)
                .build());
        circuitBreaker = CircuitBreaker.ofDefaults("authorization-test");
        authorizationClient = new AuthorizationClient(properties, restClient, retry, circuitBreaker);
    }

    @ParameterizedTest(name = "response={0}")
    @MethodSource("validAuthorizationResponses")
    void shouldReturnResponseWhenAuthorizationStatusIsValid(AuthorizationResponse expected) {
        //Arrange
        AuthorizationRequest request = validRequest();
        stubPost("/api/internal/authorize", request);
        when(responseSpec.body(AuthorizationResponse.class)).thenReturn(expected);

        //Act
        AuthorizationResponse response = authorizationClient.authorize(request);

        //Assert
        assertThat(response).isEqualTo(expected);
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithoutRetryAttempt()).isEqualTo(1);
    }

    @Test
    void shouldReturnResponseWhenAuthorizationRecoversAfterNetworkFailure() {
        //Arrange
        AuthorizationRequest request = validRequest();
        AuthorizationResponse expected = approvedResponse();
        stubPost("/api/internal/authorize", request);
        when(responseSpec.body(AuthorizationResponse.class))
                .thenThrow(new ResourceAccessException("Temporary timeout"))
                .thenReturn(expected);

        //Act
        AuthorizationResponse response = authorizationClient.authorize(request);

        //Assert
        assertThat(response).isEqualTo(expected);
        assertThat(retry.getMetrics().getNumberOfSuccessfulCallsWithRetryAttempt()).isEqualTo(1);
        assertThat(circuitBreaker.getMetrics().getNumberOfFailedCalls()).isZero();
    }

    @ParameterizedTest(name = "invalidResponse={0}")
    @MethodSource("invalidAuthorizationResponses")
    void shouldFailAfterRetriesWhenAuthorizationResponseIsInvalid(AuthorizationResponse invalidResponse) {
        //Arrange
        AuthorizationRequest request = validRequest();
        stubPost("/api/internal/authorize", request);
        when(responseSpec.body(AuthorizationResponse.class)).thenReturn(invalidResponse);

        //Act
        Throwable failure = catchThrowable(() -> authorizationClient.authorize(request));

        //Assert
        assertThat(failure).isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("STAN=" + STAN)
                .hasMessageContaining("after 3 attempts")
                .hasMessageContaining("Invalid response from Authorization");
        assertThat(((AuthorizationException) failure).getStan()).isEqualTo(STAN);
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(1);
    }

    @ParameterizedTest(name = "failure={0}, lastError={1}")
    @MethodSource("authorizationFailures")
    void shouldPreserveLastErrorWhenAuthorizationRemainsUnavailable(RuntimeException error, String expectedError) {
        //Arrange
        AuthorizationRequest request = validRequest();
        stubPost("/api/internal/authorize", request);
        when(responseSpec.body(AuthorizationResponse.class)).thenThrow(error);

        //Act
        Throwable failure = catchThrowable(() -> authorizationClient.authorize(request));

        //Assert
        assertThat(failure).isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("STAN=" + STAN)
                .hasMessageContaining("after 3 attempts")
                .hasMessageEndingWith("Last error: " + expectedError);
        assertThat(((AuthorizationException) failure).getStan()).isEqualTo(STAN);
        assertThat(retry.getMetrics().getNumberOfFailedCallsWithRetryAttempt()).isEqualTo(1);
    }

    @Test
    void shouldRejectWithoutRetryWhenCircuitBreakerIsOpen() {
        //Arrange
        AuthorizationRequest request = validRequest();
        circuitBreaker.transitionToOpenState();

        //Act
        Throwable failure = catchThrowable(() -> authorizationClient.authorize(request));

        //Assert
        assertThat(failure).isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Circuit breaker open");
        assertThat(retry.getMetrics().getNumberOfTotalCalls()).isZero();
        assertThat(circuitBreaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @ParameterizedTest(name = "rollback={0}")
    @MethodSource("validRollbackResponses")
    void shouldSendReservationDetailsWhenRollingBack(RollbackResponse expected) {
        //Arrange
        AuthorizationRequest original = validRequest();
        stubPost("/api/internal/rollback", new RollbackRequest(RRN, PAN, AMOUNT));
        when(responseSpec.body(RollbackResponse.class)).thenReturn(expected);

        //Act
        RollbackResponse response = authorizationClient.rollback(original, RRN);

        //Assert
        assertThat(response).isEqualTo(expected);
    }

    @ParameterizedTest(name = "invalidRollback={0}")
    @MethodSource("invalidRollbackResponses")
    void shouldReturnNullWhenRollbackResponseIsInvalid(RollbackResponse invalidResponse) {
        //Arrange
        AuthorizationRequest original = validRequest();
        stubPost("/api/internal/rollback", new RollbackRequest(RRN, PAN, AMOUNT));
        when(responseSpec.body(RollbackResponse.class)).thenReturn(invalidResponse);

        //Act
        RollbackResponse response = authorizationClient.rollback(original, RRN);

        //Assert
        assertThat(response).isNull();
    }

    @Test
    void shouldReturnNullWhenRollbackRequestFails() {
        //Arrange
        AuthorizationRequest original = validRequest();
        stubPost("/api/internal/rollback", new RollbackRequest(RRN, PAN, AMOUNT));
        when(responseSpec.body(RollbackResponse.class)).thenThrow(new ResourceAccessException("Timeout"));

        //Act
        RollbackResponse response = authorizationClient.rollback(original, RRN);

        //Assert
        assertThat(response).isNull();
    }

    @Test
    void shouldReportHealthyWhenAuthorizationResponds() {
        //Arrange
        stubHealthRequest();
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());

        //Act
        String health = authorizationClient.checkHealth();

        //Assert
        assertThat(health).isEqualTo("ok");
    }

    @Test
    void shouldReportDownWhenAuthorizationHealthRequestFails() {
        //Arrange
        stubHealthRequest();
        when(responseSpec.toBodilessEntity()).thenThrow(new ResourceAccessException("Timeout"));

        //Act
        String health = authorizationClient.checkHealth();

        //Assert
        assertThat(health).isEqualTo("down");
    }

    private static Stream<AuthorizationResponse> validAuthorizationResponses() {
        return Stream.of(approvedResponse(),
                new AuthorizationResponse("0110", STAN, null, null, "51", "DECLINED", "INSUFFICIENT_FUNDS", 12));
    }

    private static Stream<AuthorizationResponse> invalidAuthorizationResponses() {
        return Stream.of(null, authorizationResponse(null), authorizationResponse("PENDING"), authorizationResponse(""));
    }

    private static Stream<Arguments> authorizationFailures() {
        return Stream.of(
                Arguments.of(new ResourceAccessException("Timeout"), "Timeout"),
                Arguments.of(new IllegalStateException("Wrapper", new IOException("Connection refused")), "Connection refused"),
                Arguments.of(new IllegalStateException(), "unknown"));
    }

    private static Stream<RollbackResponse> validRollbackResponses() {
        return Stream.of(
                new RollbackResponse(RRN, "00", "APPROVED", null, 10),
                new RollbackResponse(RRN, "05", "DECLINED", "ALREADY_ROLLED_BACK", 10));
    }

    private static Stream<RollbackResponse> invalidRollbackResponses() {
        return Stream.of(null,
                new RollbackResponse(RRN, "00", null, null, 10),
                new RollbackResponse(RRN, "00", "PENDING", null, 10));
    }

    private void stubPost(String endpoint, Object request) {
        when(restClient.post()).thenReturn(postSpec);
        when(postSpec.uri(AUTHORIZATION_URL + endpoint)).thenReturn(bodySpec);
        when(bodySpec.body(request)).thenReturn(bodySpec);
        when(bodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.onStatus(any(), any())).thenReturn(responseSpec);
    }

    private void stubHealthRequest() {
        when(restClient.get()).thenAnswer(invocation -> getSpec);
        when(getSpec.uri(AUTHORIZATION_URL + "/health")).thenAnswer(invocation -> headersSpec);
        when(headersSpec.retrieve()).thenReturn(responseSpec);
    }

    private AuthorizationRequest validRequest() {
        return new AuthorizationRequest(
                "0100", STAN, PAN, "000000", AMOUNT, "643", Instant.parse("2026-01-31T23:59:59Z"),
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", "ISS001");
    }

    private static AuthorizationResponse approvedResponse() {
        return authorizationResponse("APPROVED");
    }

    private static AuthorizationResponse authorizationResponse(String status) {
        return new AuthorizationResponse("0110", STAN, RRN, "AUTH01", "00", status, null, 12);
    }
}
