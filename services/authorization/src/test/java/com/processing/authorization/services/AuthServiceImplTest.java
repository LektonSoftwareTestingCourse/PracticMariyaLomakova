package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthServiceAuthDeclineNoCardEvent;
import com.processing.authorization.events.AuthServiceAuthDeclineNoServiceEvent;
import com.processing.authorization.events.AuthServiceAuthDeclineUnknownEvent;
import com.processing.authorization.events.AuthServiceAuthDeclinedCardExpiryDateEvent;
import com.processing.authorization.events.AuthServiceAuthDeclinedCardStatusEvent;
import com.processing.authorization.events.AuthServiceAuthDeclinedFundsEvent;
import com.processing.authorization.events.AuthServiceAuthorizeEvent;
import com.processing.authorization.events.AuthorizationEvent;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.exceptions.GetCardException;
import com.processing.authorization.exceptions.InsufficientFundsException;
import com.processing.authorization.exceptions.InternalCardManagerException;
import com.processing.authorization.exceptions.InvalidGetCardRequestException;
import com.processing.authorization.exceptions.InvalidReserveRequestException;
import com.processing.authorization.exceptions.PaymentRequiredException;
import com.processing.authorization.exceptions.ReserveException;
import com.processing.authorization.exceptions.ServiceUnavailableException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

    private static final String PAN = "4000003458730237";
    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";
    private static final String STAN = "000001";
    private static final String ERROR_MESSAGE = "Card Management failed";
    private static final UUID CARD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant TRANSACTION_TIME = Instant.parse("2026-01-31T23:59:59Z");
    private static final YearMonth EXPIRY_DATE = YearMonth.of(2026, 12);
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final BigDecimal BALANCE = new BigDecimal("10000");
    private static final BigDecimal DAILY_LIMIT = new BigDecimal("100000");
    private static final BigDecimal MONTHLY_LIMIT = new BigDecimal("500000");
    private static final Map<CardModelStatus, String> DECLINED_STATUS_CODES = Map.of(
            CardModelStatus.INACTIVE, "05",
            CardModelStatus.BLOCKED, "05",
            CardModelStatus.EXPIRED, "54");

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

    @Test
    void shouldApproveAuthorizationWhenCardIsActiveAndFundsAndLimitsAreSufficient() {
        //Arrange
        AuthorizationRequest request = validRequest();
        stubCard(activeCard());
        stubAcceptedLimits(request);

        //Act
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        //Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        assertThat(response.stan()).isEqualTo(STAN);
        assertThat(response.declineReason()).isNull();
        assertThat(response.rrn()).matches("\\d{12}");
        assertThat(response.authCode()).matches("[0-9A-Z]{6}");
        verify(cardManagementClient).reserve(AMOUNT, response.rrn(), PAN);
        verify(eventNotifier).notify(new AuthServiceAuthorizeEvent(PAN));
    }

    @Test
    void shouldApproveWhenBinLookupReturnsEmpty() {
        //Arrange
        AuthorizationRequest request = validRequest();
        when(cardManagementClient.getCard(PAN)).thenReturn(activeCard());
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.empty());
        stubAcceptedLimits(request);

        //Act
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        //Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        verify(cardManagementClient).reserve(AMOUNT, response.rrn(), PAN);
        verify(eventNotifier).notify(new AuthServiceAuthorizeEvent(PAN));
    }

    @ParameterizedTest(name = "status={0}")
    @EnumSource(value = CardModelStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void shouldDeclineWithoutReservingWhenCardStatusIsNotActive(CardModelStatus status) {
        //Arrange
        AuthorizationRequest request = validRequest();
        stubCard(cardWithStatus(status));

        //Act
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        //Assert
        assertDeclined(response, DECLINED_STATUS_CODES.get(status), "CARD_" + status.name());
        verify(eventNotifier).notify(new AuthServiceAuthDeclinedCardStatusEvent(PAN, status.name()));
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void shouldDeclineWithoutReservingWhenCardStatusIsMissing() {
        //Arrange
        AuthorizationRequest request = validRequest();
        stubCard(cardWithStatus(null));

        //Act
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        //Assert
        assertDeclined(response, "05", "UNKNOWN_REASON");
        verify(eventNotifier).notify(isA(AuthServiceAuthDeclineUnknownEvent.class));
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @ParameterizedTest(name = "expiry={0}, transaction={1}")
    @CsvSource({
            "2026-01, 2026-01-30T23:59:59Z",
            "2026-01, 2026-01-31T23:59:59Z",
            "2026-02, 2026-01-31T23:59:59Z"
    })
    void shouldApproveWhenCardHasNotExpired(YearMonth expiryDate, Instant transactionTime) {
        //Arrange
        AuthorizationRequest request = request(AMOUNT, transactionTime);
        stubCard(cardWithExpiryDate(expiryDate));
        stubAcceptedLimits(request);

        //Act
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        //Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        verify(cardManagementClient).reserve(AMOUNT, response.rrn(), PAN);
        verify(eventNotifier).notify(new AuthServiceAuthorizeEvent(PAN));
    }

    @ParameterizedTest(name = "expiry={0}, transaction={1}")
    @CsvSource({
            "2025-12, 2026-01-31T23:59:59Z",
            "2026-01, 2026-02-01T00:00:00Z"
    })
    void shouldDeclineWhenCardExpiredBeforeTransaction(YearMonth expiryDate, Instant transactionTime) {
        //Arrange
        AuthorizationRequest request = request(AMOUNT, transactionTime);
        stubCard(cardWithExpiryDate(expiryDate));

        //Act
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        //Assert
        assertDeclined(response, "54", "CARD_EXPIRED");
        verify(eventNotifier).notify(new AuthServiceAuthDeclinedCardExpiryDateEvent(
                PAN, expiryDate.atEndOfMonth().toString()));
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @ParameterizedTest(name = "amount={0}, balance=10000")
    @ValueSource(strings = {"9999", "10000", "10000.00"})
    void shouldApproveWhenAmountDoesNotExceedBalance(BigDecimal amount) {
        //Arrange
        AuthorizationRequest request = request(amount, TRANSACTION_TIME);
        stubCard(activeCard());
        stubAcceptedLimits(request);

        //Act
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        //Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        verify(cardManagementClient).reserve(amount, response.rrn(), PAN);
        verify(eventNotifier).notify(new AuthServiceAuthorizeEvent(PAN));
    }

    @ParameterizedTest(name = "amount={0}, balance=10000")
    @ValueSource(strings = {"10000.01", "10001"})
    void shouldDeclineWithoutReservingWhenAmountExceedsBalance(BigDecimal amount) {
        //Arrange
        AuthorizationRequest request = request(amount, TRANSACTION_TIME);
        stubCard(activeCard());

        //Act
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        //Assert
        assertDeclined(response, "51", "INSUFFICIENT_FUNDS");
        verify(eventNotifier).notify(new AuthServiceAuthDeclinedFundsEvent(PAN));
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void shouldDeclineForCardStatusWhenStatusExpiryAndBalanceAreInvalid() {
        //Arrange
        AuthorizationRequest request = validRequest();
        stubCard(card(CardModelStatus.BLOCKED, YearMonth.of(2025, 12), BigDecimal.ZERO));

        //Act
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        //Assert
        assertDeclined(response, "05", "CARD_BLOCKED");
        verify(eventNotifier).notify(new AuthServiceAuthDeclinedCardStatusEvent(PAN, "BLOCKED"));
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void shouldDeclineForExpiryWhenExpiryAndBalanceAreInvalid() {
        //Arrange
        AuthorizationRequest request = validRequest();
        stubCard(card(CardModelStatus.ACTIVE, YearMonth.of(2025, 12), BigDecimal.ZERO));

        //Act
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        //Assert
        assertDeclined(response, "54", "CARD_EXPIRED");
        verify(eventNotifier).notify(new AuthServiceAuthDeclinedCardExpiryDateEvent(PAN, "2025-12-31"));
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @ParameterizedTest(name = "{0} -> {1}, {2}")
    @MethodSource("getCardFailures")
    void shouldDeclineWithoutReservingWhenGettingCardFails(
            RuntimeException failure, String code, String reason, AuthorizationEvent expectedEvent) {
        //Arrange
        AuthorizationRequest request = validRequest();
        when(cardManagementClient.getCard(PAN)).thenThrow(failure);

        //Act
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        //Assert
        assertDeclined(response, code, reason);
        verify(eventNotifier).notify(expectedEvent);
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @ParameterizedTest(name = "{0} -> {1}, {2}")
    @MethodSource("reservationFailures")
    void shouldDeclineWhenReservationFails(
            RuntimeException failure, String code, String reason, AuthorizationEvent expectedEvent) {
        //Arrange
        AuthorizationRequest request = validRequest();
        stubCard(activeCard());
        stubAcceptedLimits(request);
        doThrow(failure).when(cardManagementClient).reserve(eq(AMOUNT), anyString(), eq(PAN));

        //Act
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        //Assert
        assertDeclined(response, code, reason);
        verify(cardManagementClient).reserve(eq(AMOUNT), anyString(), eq(PAN));
        verify(eventNotifier).notify(expectedEvent);
        verify(eventNotifier, never()).notify(isA(AuthServiceAuthorizeEvent.class));
    }

    private static Stream<Arguments> getCardFailures() {
        AuthorizationEvent noCard = new AuthServiceAuthDeclineNoCardEvent(PAN);
        AuthorizationEvent unavailable = new AuthServiceAuthDeclineNoServiceEvent(PAN, "cmsUrl", ERROR_MESSAGE);
        return Stream.of(
                Arguments.of(new CardNotFoundException(ERROR_MESSAGE), "14", "CARD_NOT_FOUND", noCard),
                Arguments.of(new InvalidGetCardRequestException(ERROR_MESSAGE), "14", "CARD_NOT_FOUND", noCard),
                Arguments.of(new ServiceUnavailableException(ERROR_MESSAGE), "96", "SERVICE_UNAVAILABLE", unavailable),
                Arguments.of(new ResourceAccessException(ERROR_MESSAGE), "96", "SERVICE_UNAVAILABLE", unavailable),
                Arguments.of(new InternalCardManagerException(ERROR_MESSAGE), "96", "SERVICE_UNAVAILABLE", unavailable),
                Arguments.of(new GetCardException(ERROR_MESSAGE), "96", "SERVICE_UNAVAILABLE", unavailable),
                Arguments.of(new PaymentRequiredException(ERROR_MESSAGE), "05", "CARD_BLOCKED",
                        new AuthServiceAuthDeclinedCardStatusEvent(PAN, "Payment required from Card Management Service")),
                Arguments.of(new IllegalStateException(ERROR_MESSAGE), "05", "UNKNOWN_REASON",
                        new AuthServiceAuthDeclineUnknownEvent(PAN, ERROR_MESSAGE)));
    }

    private static Stream<Arguments> reservationFailures() {
        AuthorizationEvent noCard = new AuthServiceAuthDeclineNoCardEvent(PAN);
        AuthorizationEvent unavailable = new AuthServiceAuthDeclineNoServiceEvent(PAN, "cmsUrl", ERROR_MESSAGE);
        return Stream.of(
                Arguments.of(new CardNotFoundException(ERROR_MESSAGE), "14", "CARD_NOT_FOUND", noCard),
                Arguments.of(new InvalidReserveRequestException(ERROR_MESSAGE), "14", "CARD_NOT_FOUND", noCard),
                Arguments.of(new ServiceUnavailableException(ERROR_MESSAGE), "96", "SERVICE_UNAVAILABLE", unavailable),
                Arguments.of(new ResourceAccessException(ERROR_MESSAGE), "96", "SERVICE_UNAVAILABLE", unavailable),
                Arguments.of(new InternalCardManagerException(ERROR_MESSAGE), "96", "SERVICE_UNAVAILABLE", unavailable),
                Arguments.of(new InsufficientFundsException(ERROR_MESSAGE), "51", "INSUFFICIENT_FUNDS",
                        new AuthServiceAuthDeclinedFundsEvent(PAN)),
                Arguments.of(new ReserveException(ERROR_MESSAGE), "96", "RESERVATION_FAILED", unavailable),
                Arguments.of(new IllegalStateException(ERROR_MESSAGE), "05", "UNKNOWN_REASON",
                        new AuthServiceAuthDeclineUnknownEvent(PAN, ERROR_MESSAGE)));
    }

    private void stubCard(CardModel card) {
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(Optional.of(ISSUER_ID));
    }

    private void stubAcceptedLimits(AuthorizationRequest request) {
        LocalDate date = request.transmissionDateTime().atZone(ZoneOffset.UTC).toLocalDate();
        when(limitUsageRepository.upsertLimitUsage(PAN, date, request.amount(), DAILY_LIMIT, MONTHLY_LIMIT)).thenReturn(1);
        when(limitUsageRepository.fetchRrnBlock()).thenReturn(7L);
    }

    private AuthorizationRequest validRequest() {
        return request(AMOUNT, TRANSACTION_TIME);
    }

    private AuthorizationRequest request(BigDecimal amount, Instant transactionTime) {
        return new AuthorizationRequest(
                "0100", STAN, PAN, "000000", amount, "643", transactionTime,
                "TERM0001", "POS", "MERCH0000000002", "5045", "ACQ002", ISSUER_ID);
    }

    private CardModel activeCard() {
        return cardWithStatus(CardModelStatus.ACTIVE);
    }

    private CardModel cardWithStatus(CardModelStatus status) {
        return card(status, EXPIRY_DATE, BALANCE);
    }

    private CardModel cardWithExpiryDate(YearMonth expiryDate) {
        return card(CardModelStatus.ACTIVE, expiryDate, BALANCE);
    }

    private CardModel card(CardModelStatus status, YearMonth expiryDate, BigDecimal balance) {
        return new CardModel(
                CARD_ID, PAN, BIN, "IVAN IVANOV", expiryDate, status, "643",
                DAILY_LIMIT, MONTHLY_LIMIT, balance, ISSUER_ID, TRANSACTION_TIME);
    }

    private void assertDeclined(AuthorizationResponse response, String code, String reason) {
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo(code);
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.stan()).isEqualTo(STAN);
        assertThat(response.rrn()).isNull();
        assertThat(response.authCode()).isNull();
    }
}
