package com.processing.gateway.circuitbreaker;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InMemoryCircuitBreakerTest {

    private static final String SERVICE = "switch";
    private static final Instant NOW = Instant.parse("2026-01-31T12:00:00Z");
    private static final Duration OPEN_DURATION = Duration.ofSeconds(10);

    @Mock
    private Clock clock;

    private InMemoryCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        when(clock.instant()).thenReturn(NOW);
        circuitBreaker = InMemoryCircuitBreaker.forTesting(OPEN_DURATION, 2, clock);
    }

    @Test
    void shouldAllowRequestWhenCircuitIsClosed() {
        //Arrange
        String service = SERVICE;

        //Act
        boolean allowed = circuitBreaker.allowRequest(service);

        //Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldStayClosedWhenFailureCountIsBelowThreshold() {
        //Arrange
        circuitBreaker.recordFailure(SERVICE);

        //Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        //Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldOpenWhenFailureThresholdIsReached() {
        //Arrange
        openCircuit();

        //Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        //Assert
        assertThat(allowed).isFalse();
    }

    @ParameterizedTest(name = "elapsedMillis={0}")
    @ValueSource(longs = {0L, 9999L})
    void shouldRejectRequestsWhenOpenIntervalHasNotEnded(long elapsedMillis) {
        //Arrange
        openCircuit();
        when(clock.instant()).thenReturn(NOW.plusMillis(elapsedMillis));

        //Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        //Assert
        assertThat(allowed).isFalse();
    }

    @ParameterizedTest(name = "elapsedMillis={0}")
    @ValueSource(longs = {10000L, 10001L})
    void shouldAllowProbeWhenOpenIntervalHasEnded(long elapsedMillis) {
        //Arrange
        openCircuit();
        when(clock.instant()).thenReturn(NOW.plusMillis(elapsedMillis));

        //Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        //Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldRejectSecondProbeWhenFirstProbeIsInProgress() {
        //Arrange
        openCircuit();
        when(clock.instant()).thenReturn(NOW.plus(OPEN_DURATION));
        circuitBreaker.allowRequest(SERVICE);

        //Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        //Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldCloseAndResetFailureCountWhenProbeSucceeds() {
        //Arrange
        openCircuit();
        when(clock.instant()).thenReturn(NOW.plus(OPEN_DURATION));
        circuitBreaker.allowRequest(SERVICE);
        circuitBreaker.recordSuccess(SERVICE);
        circuitBreaker.recordFailure(SERVICE);

        //Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        //Assert
        assertThat(allowed).isTrue();
    }

    @ParameterizedTest(name = "millisAfterReopen={0}, allowed={1}")
    @CsvSource({"9999, false", "10000, true"})
    void shouldRestartOpenIntervalWhenProbeFails(long millisAfterReopen, boolean expected) {
        //Arrange
        openCircuit();
        Instant probeTime = NOW.plus(OPEN_DURATION);
        when(clock.instant()).thenReturn(probeTime);
        circuitBreaker.allowRequest(SERVICE);
        circuitBreaker.recordFailure(SERVICE);
        when(clock.instant()).thenReturn(probeTime.plusMillis(millisAfterReopen));

        //Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        //Assert
        assertThat(allowed).isEqualTo(expected);
    }

    @Test
    void shouldAllowAnotherProbeWhenPreviousSlotIsReleased() {
        //Arrange
        openCircuit();
        when(clock.instant()).thenReturn(NOW.plus(OPEN_DURATION));
        circuitBreaker.allowRequest(SERVICE);
        circuitBreaker.releaseRequest(SERVICE);

        //Act
        boolean allowed = circuitBreaker.allowRequest(SERVICE);

        //Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldKeepOtherServicesAvailableWhenOneCircuitIsOpen() {
        //Arrange
        openCircuit();

        //Act
        boolean allowed = circuitBreaker.allowRequest("cardManagement");

        //Assert
        assertThat(allowed).isTrue();
    }

    private void openCircuit() {
        circuitBreaker.recordFailure(SERVICE);
        circuitBreaker.recordFailure(SERVICE);
    }
}
