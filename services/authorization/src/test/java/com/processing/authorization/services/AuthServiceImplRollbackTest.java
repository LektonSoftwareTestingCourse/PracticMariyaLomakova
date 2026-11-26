package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthServiceRollbackDeclineConflictEvent;
import com.processing.authorization.events.AuthServiceRollbackDeclineNoCardEvent;
import com.processing.authorization.events.AuthServiceRollbackDeclineNoServiceEvent;
import com.processing.authorization.events.AuthServiceRollbackDeclineUnknownEvent;
import com.processing.authorization.events.AuthServiceRollbackEvent;
import com.processing.authorization.events.AuthorizationEvent;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.exceptions.InternalCardManagerException;
import com.processing.authorization.exceptions.InvalidRollbackRequestException;
import com.processing.authorization.exceptions.RollbackConflictException;
import com.processing.authorization.exceptions.RollbackFailureException;
import com.processing.authorization.exceptions.ServiceUnavailableException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplRollbackTest {

    private static final String PAN = "4000003458730237";
    private static final String RRN = "602100001000";
    private static final String ERROR_MESSAGE = "Rollback failed";
    private static final BigDecimal AMOUNT = new BigDecimal("1000");

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
    void shouldApproveRollbackWhenCardManagementReleasesReservation() {
        //Arrange
        RollbackRequest request = validRollbackRequest();

        //Act
        RollbackResponse response = authService.rollback(request, requestInputTime);

        //Assert
        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        assertThat(response.rrn()).isEqualTo(RRN);
        assertThat(response.declineReason()).isNull();
        verify(cardManagementClient).rollback(request);
        verify(eventNotifier).notify(new AuthServiceRollbackEvent(PAN));
    }

    @ParameterizedTest(name = "{0} -> {1}, {2}")
    @MethodSource("rollbackFailures")
    void shouldDeclineRollbackWhenCardManagementFails(
            RuntimeException failure, String code, String reason, AuthorizationEvent expectedEvent) {
        //Arrange
        RollbackRequest request = validRollbackRequest();
        doThrow(failure).when(cardManagementClient).rollback(request);

        //Act
        RollbackResponse response = authService.rollback(request, requestInputTime);

        //Assert
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo(code);
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.rrn()).isEqualTo(RRN);
        verify(cardManagementClient).rollback(request);
        verify(eventNotifier).notify(expectedEvent);
        verify(eventNotifier, never()).notify(isA(AuthServiceRollbackEvent.class));
    }

    private static Stream<Arguments> rollbackFailures() {
        AuthorizationEvent noCard = new AuthServiceRollbackDeclineNoCardEvent(PAN);
        AuthorizationEvent unavailable = new AuthServiceRollbackDeclineNoServiceEvent(PAN, "cmsUrl", ERROR_MESSAGE);
        return Stream.of(
                Arguments.of(new CardNotFoundException(ERROR_MESSAGE), "14", "TRANSACTION_NOT_FOUND", noCard),
                Arguments.of(new InvalidRollbackRequestException(ERROR_MESSAGE), "14", "TRANSACTION_NOT_FOUND", noCard),
                Arguments.of(new ServiceUnavailableException(ERROR_MESSAGE), "96", "SERVICE_UNAVAILABLE", unavailable),
                Arguments.of(new ResourceAccessException(ERROR_MESSAGE), "96", "SERVICE_UNAVAILABLE", unavailable),
                Arguments.of(new InternalCardManagerException(ERROR_MESSAGE), "96", "SERVICE_UNAVAILABLE", unavailable),
                Arguments.of(new RollbackConflictException(ERROR_MESSAGE), "05", "ALREADY_ROLLED_BACK",
                        new AuthServiceRollbackDeclineConflictEvent(PAN)),
                Arguments.of(new RollbackFailureException(ERROR_MESSAGE), "96", "ROLLBACK_FAILED", unavailable),
                Arguments.of(new IllegalStateException(ERROR_MESSAGE), "05", "UNKNOWN_REASON",
                        new AuthServiceRollbackDeclineUnknownEvent(PAN, ERROR_MESSAGE)));
    }

    private RollbackRequest validRollbackRequest() {
        return new RollbackRequest(RRN, PAN, AMOUNT);
    }
}
