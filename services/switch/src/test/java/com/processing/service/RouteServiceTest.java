package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    private static final String PAN = "4000003458730237";
    private static final String STAN = "000001";
    private static final String ISSUER_ID = "ISS001";
    private static final String RRN = "603100001000";
    private static final String AUTH_CODE = "AUTH01";
    private static final Instant TRANSACTION_TIME = Instant.parse("2026-01-31T23:59:59Z");
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final BigDecimal FEE = new BigDecimal("25");

    @Mock
    private RoutingService routingService;
    @Mock
    private AuthorizationClient authorizationClient;
    @Mock
    private AcquiringFeeClient acquiringFeeClient;
    @Mock
    private LoggerClient loggerClient;

    private RouteService routeService;

    @BeforeEach
    void setUp() {
        routeService = new RouteService(routingService, authorizationClient, acquiringFeeClient, loggerClient);
    }

    @Test
    void shouldPublishCompleteTransactionWhenAuthorizationIsApproved() {
        //Arrange
        AuthorizationRequest request = validRequest();
        AuthorizationResponse approved = approvedResponse(12);
        stubAuthorization(request, approved, FEE);
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(true);
        ArgumentCaptor<TransactionRequest> published = ArgumentCaptor.forClass(TransactionRequest.class);

        //Act
        AuthorizationResponse response = routeService.route(request);

        //Assert
        assertThat(response).isEqualTo(approved);
        verify(loggerClient).log(published.capture());
        TransactionRequest transaction = published.getValue();
        assertThat(transaction.id()).isNotNull();
        assertThat(transaction.mti()).isEqualTo(request.mti());
        assertThat(transaction.stan()).isEqualTo(STAN);
        assertThat(transaction.rrn()).isEqualTo(RRN);
        assertThat(transaction.pan()).isEqualTo(PAN);
        assertThat(transaction.processingCode()).isEqualTo(request.processingCode());
        assertThat(transaction.amount()).isEqualByComparingTo(AMOUNT);
        assertThat(transaction.currencyCode()).isEqualTo(request.currencyCode());
        assertThat(transaction.terminalId()).isEqualTo("TERM0001");
        assertThat(transaction.terminalType()).isEqualTo(request.terminalType());
        assertThat(transaction.merchantId()).isEqualTo("MERCH0000000002");
        assertThat(transaction.mcc()).isEqualTo(request.mcc());
        assertThat(transaction.acquirerId()).isEqualTo(request.acquirerId());
        assertThat(transaction.issuerId()).isEqualTo(ISSUER_ID);
        assertThat(transaction.acquiringFee()).isEqualByComparingTo(FEE);
        assertThat(transaction.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(transaction.declineReason()).isNull();
        assertThat(transaction.authCode()).isEqualTo(AUTH_CODE);
        assertThat(transaction.processingTimeMs()).isEqualTo(12);
        assertThat(transaction.transmissionDateTime()).isEqualTo(TRANSACTION_TIME);
        assertThat(transaction.createdAt()).isNotNull();
        verify(authorizationClient, never()).rollback(any(), anyString());
    }

    @Test
    void shouldEnrichNormalizedRequestWithIssuerWhenAuthorizingTransaction() {
        //Arrange
        AuthorizationRequest request = request(PAN, "TERM1", "MERCH00000000002");
        AuthorizationRequest normalized = request(PAN, "TERM1000", "MERCH0000000002").withIssuerId(ISSUER_ID);
        AuthorizationResponse approved = approvedResponse(12);
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(normalized)).thenReturn(approved);
        when(acquiringFeeClient.fetchAcquiringFee(TRANSACTION_TIME, STAN, PAN, "TERM1000", AMOUNT)).thenReturn(FEE);
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(true);
        ArgumentCaptor<TransactionRequest> published = ArgumentCaptor.forClass(TransactionRequest.class);

        //Act
        AuthorizationResponse response = routeService.route(request);

        //Assert
        assertThat(response).isEqualTo(approved);
        verify(loggerClient).log(published.capture());
        assertThat(published.getValue().terminalId()).isEqualTo("TERM1000");
        assertThat(published.getValue().merchantId()).isEqualTo("MERCH0000000002");
        assertThat(published.getValue().issuerId()).isEqualTo(ISSUER_ID);
    }

    @ParameterizedTest(name = "publicationAccepted={0}")
    @ValueSource(booleans = {true, false})
    void shouldPreserveDeclineWithoutRollbackWhenAuthorizationRejectsTransaction(boolean logged) {
        //Arrange
        AuthorizationRequest request = validRequest();
        AuthorizationResponse declined = declinedResponse();
        stubAuthorization(request, declined, null);
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(logged);
        ArgumentCaptor<TransactionRequest> published = ArgumentCaptor.forClass(TransactionRequest.class);

        //Act
        AuthorizationResponse response = routeService.route(request);

        //Assert
        assertThat(response).isEqualTo(declined);
        verify(loggerClient).log(published.capture());
        assertThat(published.getValue().status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(published.getValue().declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(published.getValue().acquiringFee()).isNull();
        verify(authorizationClient, never()).rollback(any(), anyString());
    }

    @ParameterizedTest(name = "pan={0}")
    @NullAndEmptySource
    @ValueSource(strings = {"40000", "9999990000000000"})
    void shouldPublishDeclineWhenBinCannotBeRouted(String pan) {
        //Arrange
        AuthorizationRequest request = request(pan, "TERM0001", "MERCH0000000002");
        when(routingService.getIssuerIdByPan(pan)).thenThrow(new UnknownBinException("unknown"));
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(true);
        ArgumentCaptor<TransactionRequest> published = ArgumentCaptor.forClass(TransactionRequest.class);

        //Act
        AuthorizationResponse response = routeService.route(request);

        //Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("14");
        assertThat(response.declineReason()).isEqualTo("CARD_NOT_FOUND");
        assertThat(response.stan()).isEqualTo(STAN);
        verify(loggerClient).log(published.capture());
        assertThat(published.getValue().pan()).isEqualTo(pan);
        assertThat(published.getValue().status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(published.getValue().acquiringFee()).isNull();
        verify(authorizationClient, never()).rollback(any(), anyString());
    }

    @Test
    void shouldPublishDeclineWhenAuthorizationIsUnavailable() {
        //Arrange
        AuthorizationRequest request = validRequest();
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(request.withIssuerId(ISSUER_ID)))
                .thenThrow(new AuthorizationException(STAN, 3, "Timeout"));
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(true);
        ArgumentCaptor<TransactionRequest> published = ArgumentCaptor.forClass(TransactionRequest.class);

        //Act
        AuthorizationResponse response = routeService.route(request);

        //Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("05");
        assertThat(response.declineReason()).isEqualTo("Authorization service unavailable");
        verify(loggerClient).log(published.capture());
        assertThat(published.getValue().issuerId()).isEqualTo(ISSUER_ID);
        assertThat(published.getValue().status()).isEqualTo(TransactionStatus.DECLINED);
        verify(authorizationClient, never()).rollback(any(), anyString());
    }

    @ParameterizedTest(name = "rollback={0}")
    @MethodSource("rollbackResults")
    void shouldRollbackAndReturnSystemErrorWhenPublishingApprovedTransactionFails(RollbackResponse rollback) {
        //Arrange
        AuthorizationRequest request = validRequest();
        stubAuthorization(request, approvedResponse(12), FEE);
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(false);
        when(authorizationClient.rollback(request.withIssuerId(ISSUER_ID), RRN)).thenReturn(rollback);

        //Act
        AuthorizationResponse response = routeService.route(request);

        //Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo("96");
        assertThat(response.declineReason()).isEqualTo("System error");
        assertThat(response.stan()).isEqualTo(STAN);
        assertThat(response.rrn()).isNull();
        assertThat(response.authCode()).isNull();
        verify(loggerClient).log(any(TransactionRequest.class));
        verify(authorizationClient).rollback(request.withIssuerId(ISSUER_ID), RRN);
    }

    @Test
    void shouldPublishZeroProcessingTimeWhenAuthorizationDoesNotProvideIt() {
        //Arrange
        AuthorizationRequest request = validRequest();
        AuthorizationResponse approved = approvedResponse(null);
        stubAuthorization(request, approved, FEE);
        when(loggerClient.log(any(TransactionRequest.class))).thenReturn(true);
        ArgumentCaptor<TransactionRequest> published = ArgumentCaptor.forClass(TransactionRequest.class);

        //Act
        AuthorizationResponse response = routeService.route(request);

        //Assert
        assertThat(response).isEqualTo(approved);
        verify(loggerClient).log(published.capture());
        assertThat(published.getValue().processingTimeMs()).isZero();
    }

    private static Stream<Arguments> rollbackResults() {
        return Stream.of(
                Arguments.of(new RollbackResponse(RRN, "00", "APPROVED", null, 10)),
                Arguments.of(new RollbackResponse(RRN, "05", "DECLINED", "ALREADY_ROLLED_BACK", 10)),
                Arguments.of((RollbackResponse) null));
    }

    private void stubAuthorization(AuthorizationRequest request, AuthorizationResponse response, BigDecimal fee) {
        when(routingService.getIssuerIdByPan(PAN)).thenReturn(ISSUER_ID);
        when(authorizationClient.authorize(request.withIssuerId(ISSUER_ID))).thenReturn(response);
        when(acquiringFeeClient.fetchAcquiringFee(TRANSACTION_TIME, STAN, PAN, "TERM0001", AMOUNT)).thenReturn(fee);
    }

    private AuthorizationRequest validRequest() {
        return request(PAN, "TERM0001", "MERCH0000000002");
    }

    private AuthorizationRequest request(String pan, String terminalId, String merchantId) {
        return new AuthorizationRequest(
                "0100", STAN, pan, "000000", AMOUNT, "643", TRANSACTION_TIME,
                terminalId, "POS", merchantId, "5045", "ACQ002", null);
    }

    private AuthorizationResponse approvedResponse(Integer processingTime) {
        return new AuthorizationResponse("0110", STAN, RRN, AUTH_CODE, "00", "APPROVED", null, processingTime);
    }

    private AuthorizationResponse declinedResponse() {
        return new AuthorizationResponse("0110", STAN, null, null, "51", "DECLINED", "INSUFFICIENT_FUNDS", 12);
    }
}
