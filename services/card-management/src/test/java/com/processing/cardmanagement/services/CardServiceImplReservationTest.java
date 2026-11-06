package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.events.CardServiceReserveEvent;
import com.processing.cardmanagement.events.CardServiceRollbackEvent;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import com.processing.cardmanagement.exceptions.ReservationAlreadyExistsException;
import com.processing.cardmanagement.exceptions.ReservationNotFoundException;
import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.models.Reservation;
import com.processing.cardmanagement.models.ReservationRollback;
import com.processing.cardmanagement.models.ReservationStatus;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryServiceImpl;
import com.processing.cardmanagement.support.DirectTransactionRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardServiceImplReservationTest {

    private static final String PAN = "4000003458730237";
    private static final String RRN = "603100001000";
    private static final UUID CARD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RESERVATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("1000");
    private static final BigDecimal BALANCE = new BigDecimal("10000");

    @Mock
    private CardRepository cardRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationRollbackRepository rollbackRepository;
    @Mock
    private CardServiceSettings settings;
    @Mock
    private CardServiceDefaults defaults;
    @Mock
    private PanGenerator panGenerator;
    @Mock
    private CardEventNotifier eventNotifier;
    @Mock
    private BinIssuerService binIssuerService;

    private CardServiceImpl cardService;

    @BeforeEach
    void setUp() {
        cardService = new CardServiceImpl(
                cardRepository, reservationRepository, rollbackRepository, settings, defaults, panGenerator,
                eventNotifier, binIssuerService, new RetryServiceImpl(), new DirectTransactionRunner());
    }

    @ParameterizedTest(name = "amount={0}, remainingBalance={1}")
    @CsvSource({"1, 9999", "9999, 1", "10000, 0", "10000.00, 0.00"})
    void shouldSaveReservationAndSubtractFundsWhenBalanceIsSufficient(BigDecimal amount, BigDecimal expectedBalance) {
        //Arrange
        Card original = cardWithBalance(BALANCE);
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(original));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(true);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cardRepository.update(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<Reservation> saved = ArgumentCaptor.forClass(Reservation.class);

        //Act
        Card updated = cardService.reserve(PAN, amount, RRN);

        //Assert
        assertThat(updated.availableBalance()).isEqualByComparingTo(expectedBalance);
        assertThat(updated).usingRecursiveComparison().ignoringFields("availableBalance").isEqualTo(original);
        assertThat(original.availableBalance()).isEqualByComparingTo(BALANCE);
        verify(reservationRepository).save(saved.capture());
        assertThat(saved.getValue().id()).isNotNull();
        assertThat(saved.getValue().pan()).isEqualTo(PAN);
        assertThat(saved.getValue().rrn()).isEqualTo(RRN);
        assertThat(saved.getValue().reservationAmount()).isEqualByComparingTo(amount);
        assertThat(saved.getValue().status()).isEqualTo(ReservationStatus.RESERVED);
        verify(cardRepository).update(updated);
        verify(eventNotifier).notifyListeners(new CardServiceReserveEvent(PAN, RRN, amount));
    }

    @ParameterizedTest(name = "amount={0}, balance=10000")
    @ValueSource(strings = {"10000.01", "10001"})
    void shouldRejectWithoutSavingWhenReservationExceedsBalance(BigDecimal amount) {
        //Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(cardWithBalance(BALANCE)));

        //Act
        Throwable failure = catchThrowable(() -> cardService.reserve(PAN, amount, RRN));

        //Assert
        assertThat(failure).isInstanceOf(InsufficientFundsException.class);
        verify(reservationRepository, never()).save(any(Reservation.class));
        verify(cardRepository, never()).update(any(Card.class));
        verifyNoInteractions(eventNotifier);
    }

    @ParameterizedTest(name = "status={0}")
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void shouldRejectWithoutSavingWhenCardIsNotActive(CardStatus status) {
        //Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card(status, BALANCE)));

        //Act
        Throwable failure = catchThrowable(() -> cardService.reserve(PAN, AMOUNT, RRN));

        //Assert
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessageContaining(status.name());
        verify(reservationRepository, never()).save(any(Reservation.class));
        verify(cardRepository, never()).update(any(Card.class));
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldRejectWithoutSavingWhenReservationAlreadyExists() {
        //Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(cardWithBalance(BALANCE)));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(false);

        //Act
        Throwable failure = catchThrowable(() -> cardService.reserve(PAN, AMOUNT, RRN));

        //Assert
        assertThat(failure).isInstanceOf(ReservationAlreadyExistsException.class).hasMessageContaining(RRN);
        verify(reservationRepository, never()).save(any(Reservation.class));
        verify(cardRepository, never()).update(any(Card.class));
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldRejectReservationWhenCardDoesNotExist() {
        //Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.empty());

        //Act
        Throwable failure = catchThrowable(() -> cardService.reserve(PAN, AMOUNT, RRN));

        //Assert
        assertThat(failure).isInstanceOf(CardNotFoundException.class);
        verify(reservationRepository, never()).save(any(Reservation.class));
        verify(cardRepository, never()).update(any(Card.class));
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldNotNotifyWhenSavingReservedBalanceFails() {
        //Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(cardWithBalance(BALANCE)));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(true);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        var databaseFailure = new DataAccessResourceFailureException("Storage unavailable");
        when(cardRepository.update(any(Card.class))).thenThrow(databaseFailure);

        //Act
        Throwable failure = catchThrowable(() -> cardService.reserve(PAN, AMOUNT, RRN));

        //Assert
        assertThat(failure).isExactlyInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage(databaseFailure.getMessage());
        verifyNoInteractions(eventNotifier);
    }

    @ParameterizedTest(name = "amount={0}, restoredBalance={1}")
    @CsvSource({"1, 9001", "999, 9999", "1000, 10000"})
    void shouldSaveRollbackAndRestoreFundsWhenReservationExists(BigDecimal amount, BigDecimal expectedBalance) {
        //Arrange
        Card original = cardWithBalance(new BigDecimal("9000"));
        Reservation reservation = reservation(ReservationStatus.RESERVED, amount);
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(original));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(reservation));
        when(rollbackRepository.save(any(ReservationRollback.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(cardRepository.update(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<ReservationRollback> rollback = ArgumentCaptor.forClass(ReservationRollback.class);
        ArgumentCaptor<Reservation> savedReservation = ArgumentCaptor.forClass(Reservation.class);

        //Act
        Card updated = cardService.rollback(PAN, amount, RRN);

        //Assert
        assertThat(updated.availableBalance()).isEqualByComparingTo(expectedBalance);
        assertThat(updated).usingRecursiveComparison().ignoringFields("availableBalance").isEqualTo(original);
        assertThat(original.availableBalance()).isEqualByComparingTo("9000");
        verify(rollbackRepository).save(rollback.capture());
        assertThat(rollback.getValue().id()).isNotNull();
        assertThat(rollback.getValue().reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(rollback.getValue().pan()).isEqualTo(PAN);
        assertThat(rollback.getValue().rrn()).isEqualTo(RRN);
        assertThat(rollback.getValue().rollbackAmount()).isEqualByComparingTo(amount);
        verify(reservationRepository).save(savedReservation.capture());
        assertThat(savedReservation.getValue().status()).isEqualTo(ReservationStatus.ROLLED_BACK);
        assertThat(savedReservation.getValue().id()).isEqualTo(RESERVATION_ID);
        assertThat(savedReservation.getValue().reservationAmount()).isEqualByComparingTo(amount);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
        verify(cardRepository).update(updated);
        verify(eventNotifier).notifyListeners(new CardServiceRollbackEvent(PAN, RRN, amount));
    }

    @Test
    void shouldRejectRollbackWhenCardDoesNotExist() {
        //Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.empty());

        //Act
        Throwable failure = catchThrowable(() -> cardService.rollback(PAN, AMOUNT, RRN));

        //Assert
        assertThat(failure).isInstanceOf(CardNotFoundException.class);
        verify(rollbackRepository, never()).save(any(ReservationRollback.class));
        verify(reservationRepository, never()).save(any(Reservation.class));
        verify(cardRepository, never()).update(any(Card.class));
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldRejectRollbackWhenReservationDoesNotExist() {
        //Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(cardWithBalance(BALANCE)));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.empty());

        //Act
        Throwable failure = catchThrowable(() -> cardService.rollback(PAN, AMOUNT, RRN));

        //Assert
        assertThat(failure).isInstanceOf(ReservationNotFoundException.class).hasMessageContaining(RRN);
        verify(rollbackRepository, never()).save(any(ReservationRollback.class));
        verify(reservationRepository, never()).save(any(Reservation.class));
        verify(cardRepository, never()).update(any(Card.class));
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldRejectRollbackWhenReservationIsAlreadyRolledBack() {
        //Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(cardWithBalance(BALANCE)));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN))
                .thenReturn(Optional.of(reservation(ReservationStatus.ROLLED_BACK, AMOUNT)));

        //Act
        Throwable failure = catchThrowable(() -> cardService.rollback(PAN, AMOUNT, RRN));

        //Assert
        assertThat(failure).isInstanceOf(RollbackAlreadySatisfiedException.class).hasMessageContaining(RRN);
        verify(rollbackRepository, never()).save(any(ReservationRollback.class));
        verify(reservationRepository, never()).save(any(Reservation.class));
        verify(cardRepository, never()).update(any(Card.class));
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldNotUpdateReservationOrBalanceWhenSavingRollbackFails() {
        //Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(cardWithBalance(BALANCE)));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN))
                .thenReturn(Optional.of(reservation(ReservationStatus.RESERVED, AMOUNT)));
        var databaseFailure = new DataAccessResourceFailureException("Storage unavailable");
        when(rollbackRepository.save(any(ReservationRollback.class))).thenThrow(databaseFailure);

        //Act
        Throwable failure = catchThrowable(() -> cardService.rollback(PAN, AMOUNT, RRN));

        //Assert
        assertThat(failure).isExactlyInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage(databaseFailure.getMessage());
        verify(reservationRepository, never()).save(any(Reservation.class));
        verify(cardRepository, never()).update(any(Card.class));
        verifyNoInteractions(eventNotifier);
    }

    private Card cardWithBalance(BigDecimal balance) {
        return card(CardStatus.ACTIVE, balance);
    }

    private Card card(CardStatus status, BigDecimal balance) {
        return new Card(CARD_ID, PAN, "400000", "IVAN IVANOV", YearMonth.of(2029, 1), status, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), balance, "ISS001", CREATED_AT);
    }

    private Reservation reservation(ReservationStatus status, BigDecimal amount) {
        return new Reservation(RESERVATION_ID, PAN, amount, RRN, status, CREATED_AT, CREATED_AT);
    }
}
