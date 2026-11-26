package com.processing.gateway.ratelimit;

import com.github.benmanes.caffeine.cache.Ticker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InMemoryRateLimiterTest {

    private static final String CLIENT = "POST /api/transactions:192.0.2.1";
    private static final String OTHER_CLIENT = "POST /api/transactions:192.0.2.2";
    private static final Duration EXPIRATION = Duration.ofMinutes(10);

    @Mock
    private Ticker ticker;

    @BeforeEach
    void setUp() {
        when(ticker.read()).thenReturn(0L);
    }

    @Test
    void shouldAllowFirstRequestWhenBucketIsFull() {
        //Arrange
        InMemoryRateLimiter limiter = limiter(2, 1);

        //Act
        boolean allowed = limiter.allowRequest(CLIENT);

        //Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldAllowRequestWhenItConsumesLastToken() {
        //Arrange
        InMemoryRateLimiter limiter = limiter(2, 1);
        limiter.allowRequest(CLIENT);

        //Act
        boolean allowed = limiter.allowRequest(CLIENT);

        //Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldRejectRequestWhenCapacityIsExhausted() {
        //Arrange
        InMemoryRateLimiter limiter = limiter(2, 1);
        limiter.allowRequest(CLIENT);
        limiter.allowRequest(CLIENT);

        //Act
        boolean allowed = limiter.allowRequest(CLIENT);

        //Assert
        assertThat(allowed).isFalse();
    }

    @Test
    void shouldKeepSeparateBucketsWhenClientsDiffer() {
        //Arrange
        InMemoryRateLimiter limiter = limiter(1, 0);
        limiter.allowRequest(CLIENT);

        //Act
        boolean allowed = limiter.allowRequest(OTHER_CLIENT);

        //Assert
        assertThat(allowed).isTrue();
    }

    @ParameterizedTest(name = "elapsedNanos={0}, allowed={1}")
    @CsvSource({"0, false", "499999999, false", "500000000, true", "1000000000, true"})
    void shouldApplyConfiguredRefillRateWhenRequestArrives(long elapsedNanos, boolean expected) {
        //Arrange
        InMemoryRateLimiter limiter = limiter(1, 2);
        limiter.allowRequest(CLIENT);
        when(ticker.read()).thenReturn(elapsedNanos);

        //Act
        boolean allowed = limiter.allowRequest(CLIENT);

        //Assert
        assertThat(allowed).isEqualTo(expected);
    }

    @Test
    void shouldKeepFractionalTokensWhenRequestsArriveBeforeRefillCompletes() {
        //Arrange
        InMemoryRateLimiter limiter = limiter(1, 2);
        limiter.allowRequest(CLIENT);
        when(ticker.read()).thenReturn(250_000_000L);
        limiter.allowRequest(CLIENT);
        when(ticker.read()).thenReturn(500_000_000L);

        //Act
        boolean allowed = limiter.allowRequest(CLIENT);

        //Assert
        assertThat(allowed).isTrue();
    }

    @Test
    void shouldCapRefilledTokensWhenBucketCapacityWouldBeExceeded() {
        //Arrange
        InMemoryRateLimiter limiter = limiter(2, 1);
        limiter.allowRequest(CLIENT);
        limiter.allowRequest(CLIENT);
        when(ticker.read()).thenReturn(Duration.ofSeconds(5).toNanos());
        limiter.allowRequest(CLIENT);
        limiter.allowRequest(CLIENT);

        //Act
        boolean allowed = limiter.allowRequest(CLIENT);

        //Assert
        assertThat(allowed).isFalse();
    }

    @ParameterizedTest(name = "capacity={0}")
    @ValueSource(ints = {0, -1})
    void shouldRejectRequestWhenCapacityIsNotPositive(int capacity) {
        //Arrange
        InMemoryRateLimiter limiter = limiter(capacity, 1);

        //Act
        boolean allowed = limiter.allowRequest(CLIENT);

        //Assert
        assertThat(allowed).isFalse();
    }

    @ParameterizedTest(name = "refillPerSecond={0}")
    @ValueSource(doubles = {0, -1})
    void shouldNotRefillWhenConfiguredRateIsNotPositive(double refillPerSecond) {
        //Arrange
        InMemoryRateLimiter limiter = limiter(1, refillPerSecond);
        limiter.allowRequest(CLIENT);
        when(ticker.read()).thenReturn(Duration.ofSeconds(60).toNanos());

        //Act
        boolean allowed = limiter.allowRequest(CLIENT);

        //Assert
        assertThat(allowed).isFalse();
    }

    @ParameterizedTest(name = "idleMillis={0}, allowed={1}")
    @CsvSource({"9999, false", "10000, true", "10001, true"})
    void shouldApplyIdleExpirationWhenClientReturns(long idleMillis, boolean expected) {
        //Arrange
        InMemoryRateLimiter limiter = InMemoryRateLimiter.forTesting(1, 0, Duration.ofSeconds(10), 100, ticker);
        limiter.allowRequest(CLIENT);
        when(ticker.read()).thenReturn(Duration.ofMillis(idleMillis).toNanos());
        limiter.cleanUp();

        //Act
        boolean allowed = limiter.allowRequest(CLIENT);

        //Assert
        assertThat(allowed).isEqualTo(expected);
    }

    private InMemoryRateLimiter limiter(int capacity, double refillPerSecond) {
        return InMemoryRateLimiter.forTesting(capacity, refillPerSecond, EXPIRATION, 100, ticker);
    }
}
