package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthServiceAuthDeclineNoServiceEvent;
import com.processing.authorization.events.AuthServiceAuthDeclinedLimitsEvent;
import com.processing.authorization.events.AuthServiceAuthorizeEvent;
import com.processing.authorization.events.AuthServiceDuplicateKeyEvent;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplLimitsTest {

    private static final String PAN = "4000003458730237";
    private static final String ISSUER_ID = "ISS001";
    private static final String CONFLICT_MESSAGE = "Limit usage already exists";
    private static final String DATABASE_ERROR = "Limit storage unavailable";
    private static final Instant TRANSACTION_TIME = Instant.parse("2026-01-31T23:59:59Z");
    private static final LocalDate TRANSACTION_DATE = LocalDate.of(2026, 1, 31);
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final BigDecimal DAILY_LIMIT = new BigDecimal("100000");
    private static final BigDecimal MONTHLY_LIMIT = new BigDecimal("500000");

    @Mock
    private LimitUsageRepository limitUsageRepository;
    @Mock
    private AuthorizationEventNotifier eventNotifier;
    @Mock
    private BinLookupClient binLookupClient;
    @Mock
    private CardManagementClient cardManagementClient;

    private AuthServiceImpl authService;
    private Instant requestInputTime;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                limitUsageRepository, eventNotifier, binLookupClient, cardManagementClient);
        requestInputTime = Instant.now();
    }

    @ParameterizedTest(name = "updatedRows={0}, accepted={1}")
    @CsvSource({"0, false", "1, true"})
    void shouldReportAcceptanceWhenRepositoryReturnsUpdatedRowCount(int updatedRows, boolean expected) {
        //Arrange
        CardModel card = activeCard();
        when(limitUsageRepository.upsertLimitUsage(PAN, TRANSACTION_DATE, AMOUNT, DAILY_LIMIT, MONTHLY_LIMIT))
                .thenReturn(updatedRows);

        //Act
        boolean accepted = authService.checkAndUpdateLimits(card, AMOUNT, TRANSACTION_DATE);

        //Assert
        assertThat(accepted).isEqualTo(expected);
    }

    @Test
    void shouldDeclineWithoutReservingWhenRepositoryRejectsLimitUsage() {
        //Arrange
        AuthorizationRequest request = validRequest();
        stubActiveCard();
        when(limitUsageRepository.upsertLimitUsage(PAN, TRANSACTION_DATE, AMOUNT, DAILY_LIMIT, MONTHLY_LIMIT))
                .thenReturn(0);

        //Act
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        //Assert
        assertDeclined(response, "61", "EXCEEDS_AMOUNT_LIMIT");
        verify(eventNotifier).notify(new AuthServiceAuthDeclinedLimitsEvent(PAN));
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @ParameterizedTest(name = "transaction={0}, limitDate={1}")
    @CsvSource({
            "2026-01-31T23:59:59Z, 2026-01-31",
            "2026-02-01T00:00:00Z, 2026-02-01"
    })
    void shouldUseUtcTransactionDateWhenUpdatingLimits(Instant transactionTime, LocalDate expectedDate) {
        //Arrange
        AuthorizationRequest request = requestAt(transactionTime);
        stubActiveCard();
        when(limitUsageRepository.upsertLimitUsage(PAN, expectedDate, AMOUNT, DAILY_LIMIT, MONTHLY_LIMIT))
                .thenReturn(1);
        when(limitUsageRepository.fetchRrnBlock()).thenReturn(7L);

        //Act
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        //Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        verify(cardManagementClient).reserve(AMOUNT, response.rrn(), PAN);
        verify(eventNotifier).notify(new AuthServiceAuthorizeEvent(PAN));
    }

    @ParameterizedTest(name = "failure={0}")
    @MethodSource("databaseFailures")
    void shouldDeclineWithoutReservingWhenLimitRepositoryIsUnavailable(RuntimeException failure) {
        //Arrange
        AuthorizationRequest request = validRequest();
        stubActiveCard();
        when(limitUsageRepository.upsertLimitUsage(PAN, TRANSACTION_DATE, AMOUNT, DAILY_LIMIT, MONTHLY_LIMIT))
                .thenThrow(failure);

        //Act
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        //Assert
        assertDeclined(response, "96", "DB_UNAVAILABLE");
        verify(eventNotifier).notify(new AuthServiceAuthDeclineNoServiceEvent(
                PAN, "limit_usage table", failure.getMessage()));
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @ParameterizedTest(name = "conflict={0}")
    @MethodSource("limitConflicts")
    void shouldApproveWhenLimitUpdateSucceedsAfterConflict(RuntimeException conflict) {
        //Arrange
        AuthorizationRequest request = validRequest();
        stubActiveCard();
        when(limitUsageRepository.upsertLimitUsage(PAN, TRANSACTION_DATE, AMOUNT, DAILY_LIMIT, MONTHLY_LIMIT))
                .thenThrow(conflict).thenReturn(1);
        when(limitUsageRepository.fetchRrnBlock()).thenReturn(7L);

        //Act
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        //Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        verify(cardManagementClient).reserve(AMOUNT, response.rrn(), PAN);
        verify(eventNotifier).notify(new AuthServiceDuplicateKeyEvent(PAN, conflict.getMessage()));
        verify(eventNotifier).notify(new AuthServiceAuthorizeEvent(PAN));
    }

    @ParameterizedTest(name = "conflict={0}")
    @MethodSource("limitConflicts")
    void shouldDeclineWithoutReservingWhenRetryRejectsLimitUsage(RuntimeException conflict) {
        //Arrange
        AuthorizationRequest request = validRequest();
        stubActiveCard();
        when(limitUsageRepository.upsertLimitUsage(PAN, TRANSACTION_DATE, AMOUNT, DAILY_LIMIT, MONTHLY_LIMIT))
                .thenThrow(conflict).thenReturn(0);

        //Act
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        //Assert
        assertDeclined(response, "61", "EXCEEDS_AMOUNT_LIMIT");
        verify(eventNotifier).notify(new AuthServiceDuplicateKeyEvent(PAN, conflict.getMessage()));
        verify(eventNotifier).notify(new AuthServiceAuthDeclinedLimitsEvent(PAN));
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @ParameterizedTest(name = "conflict={0}, retryFailure={1}")
    @MethodSource("failedLimitRetries")
    void shouldDeclineWithoutReservingWhenLimitUpdateRetryFails(
            RuntimeException conflict, RuntimeException retryFailure) {
        //Arrange
        AuthorizationRequest request = validRequest();
        stubActiveCard();
        when(limitUsageRepository.upsertLimitUsage(PAN, TRANSACTION_DATE, AMOUNT, DAILY_LIMIT, MONTHLY_LIMIT))
                .thenThrow(conflict).thenThrow(retryFailure);

        //Act
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        //Assert
        assertDeclined(response, "96", "DB_UNAVAILABLE");
        verify(eventNotifier).notify(new AuthServiceDuplicateKeyEvent(PAN, conflict.getMessage()));
        verify(eventNotifier).notify(new AuthServiceAuthDeclineNoServiceEvent(
                PAN, "limit_usage table", retryFailure.getMessage()));
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    private static Stream<RuntimeException> databaseFailures() {
        return Stream.of(
                new DataAccessResourceFailureException(DATABASE_ERROR),
                new IllegalStateException(DATABASE_ERROR));
    }

    private static Stream<RuntimeException> limitConflicts() {
        return Stream.of(
                new DuplicateKeyException(CONFLICT_MESSAGE),
                new ConstraintViolationException(CONFLICT_MESSAGE, new SQLException(CONFLICT_MESSAGE), "limit_usage_pk"));
    }

    private static Stream<Arguments> failedLimitRetries() {
        return limitConflicts().flatMap(conflict -> Stream.of(
                Arguments.of(conflict, new DataAccessResourceFailureException(DATABASE_ERROR)),
                Arguments.of(conflict, new DuplicateKeyException(CONFLICT_MESSAGE))));
    }

    private void stubActiveCard() {
        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
    }

    private CardModel activeCard() {
        return new CardModel(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), PAN, "400000", "IVAN IVANOV",
                YearMonth.of(2026, 12), CardModelStatus.ACTIVE, "643", DAILY_LIMIT, MONTHLY_LIMIT,
                new BigDecimal("10000"), ISSUER_ID, TRANSACTION_TIME);
    }

    private AuthorizationRequest validRequest() {
        return requestAt(TRANSACTION_TIME);
    }

    private AuthorizationRequest requestAt(Instant transactionTime) {
        return new AuthorizationRequest(
                "0100", "000001", PAN, "000000", AMOUNT, "643", transactionTime,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", ISSUER_ID);
    }

    private void assertDeclined(AuthorizationResponse response, String code, String reason) {
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo(code);
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.rrn()).isNull();
        assertThat(response.authCode()).isNull();
    }
}
