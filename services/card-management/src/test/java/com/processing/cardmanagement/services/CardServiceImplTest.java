package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.events.CardServiceDeletionEvent;
import com.processing.cardmanagement.events.CardServicePatchEvent;
import com.processing.cardmanagement.events.CardsAmountUpdatedEvent;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.exceptions.TooLargeLimitException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardStatus;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {

    private static final String PAN = "4000003458730237";
    private static final String SECOND_PAN = "4000000000000002";
    private static final String BIN = "400000";
    private static final String ISSUER_ID = "ISS001";
    private static final UUID CARD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_CARD_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant END_DATE = Instant.parse("2026-02-01T00:00:00Z");
    private static final BigDecimal DAILY_LIMIT = new BigDecimal("100000");
    private static final BigDecimal MONTHLY_LIMIT = new BigDecimal("500000");
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

    @Test
    void shouldReturnCardWhenPanExists() {
        //Arrange
        Card expected = activeCard();
        when(cardRepository.findByPan(PAN)).thenReturn(Optional.of(expected));

        //Act
        Card card = cardService.getCard(PAN);

        //Assert
        assertThat(card).isEqualTo(expected);
    }

    @Test
    void shouldThrowWhenRequestedCardDoesNotExist() {
        //Arrange
        when(cardRepository.findByPan(PAN)).thenReturn(Optional.empty());

        //Act
        Throwable failure = catchThrowable(() -> cardService.getCard(PAN));

        //Assert
        assertThat(failure).isInstanceOf(CardNotFoundException.class);
    }

    @Test
    void shouldUseDefaultPaginationWhenLimitAndOffsetAreMissing() {
        //Arrange
        Card expected = activeCard();
        when(defaults.pageLimit()).thenReturn(50);
        when(defaults.pageOffset()).thenReturn(0L);
        when(cardRepository.findCards(50, 0L, null, null, null, null, null)).thenReturn(List.of(expected));

        //Act
        List<Card> cards = cardService.getCards(null, null, null, null, null, null, null);

        //Assert
        assertThat(cards).containsExactly(expected);
    }

    @ParameterizedTest(name = "limit={0}, max=100")
    @ValueSource(ints = {1, 99, 100})
    void shouldAcceptLimitAndUseDefaultOffsetWhenPageLimitIsWithinMaximum(int limit) {
        //Arrange
        Card expected = activeCard();
        when(settings.maxPageLimit()).thenReturn(100);
        when(defaults.pageOffset()).thenReturn(0L);
        when(cardRepository.findCards(limit, 0L, null, null, null, null, null)).thenReturn(List.of(expected));

        //Act
        List<Card> cards = cardService.getCards(limit, null, null, null, null, null, null);

        //Assert
        assertThat(cards).containsExactly(expected);
    }

    @ParameterizedTest(name = "offset={0}")
    @ValueSource(longs = {0L, 10L})
    void shouldPreserveExplicitOffsetWhenUsingDefaultLimit(long offset) {
        //Arrange
        Card expected = activeCard();
        when(defaults.pageLimit()).thenReturn(50);
        when(cardRepository.findCards(50, offset, null, null, null, null, null)).thenReturn(List.of(expected));

        //Act
        List<Card> cards = cardService.getCards(null, offset, null, null, null, null, null);

        //Assert
        assertThat(cards).containsExactly(expected);
    }

    @ParameterizedTest(name = "limit={0}, max=100")
    @ValueSource(ints = {101, Integer.MAX_VALUE})
    void shouldRejectWhenPageLimitExceedsMaximum(int limit) {
        //Arrange
        when(settings.maxPageLimit()).thenReturn(100);

        //Act
        Throwable failure = catchThrowable(() -> cardService.getCards(limit, 0L, null, null, null, null, null));

        //Assert
        assertThat(failure).isInstanceOf(TooLargeLimitException.class)
                .hasMessage("Page limit (" + limit + " received) can not be more than 100");
    }

    @ParameterizedTest(name = "status={0}")
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "DELETED")
    void shouldPassPaginationAndAllFiltersWhenQueryingRepository(CardStatus status) {
        //Arrange
        Card expected = cardWithData(status, DAILY_LIMIT, MONTHLY_LIMIT, BALANCE);
        when(settings.maxPageLimit()).thenReturn(100);
        when(cardRepository.findCards(10, 25L, status, BIN, ISSUER_ID, CREATED_AT, END_DATE))
                .thenReturn(List.of(expected));

        //Act
        List<Card> cards = cardService.getCards(10, 25L, status, BIN, ISSUER_ID, CREATED_AT, END_DATE);

        //Assert
        assertThat(cards).containsExactly(expected);
    }

    @Test
    void shouldReturnEmptyListWhenNoCardsMatch() {
        //Arrange
        when(settings.maxPageLimit()).thenReturn(100);
        when(cardRepository.findCards(10, 0L, CardStatus.BLOCKED, BIN, null, null, null)).thenReturn(List.of());

        //Act
        List<Card> cards = cardService.getCards(10, 0L, CardStatus.BLOCKED, BIN, null, null, null);

        //Assert
        assertThat(cards).isEmpty();
    }

    @ParameterizedTest(name = "status={0}, daily={1}, monthly={2}, balance={3}")
    @CsvSource(value = {
            "NULL, NULL, NULL, NULL, ACTIVE, 100000, 500000, 10000",
            "BLOCKED, NULL, NULL, NULL, BLOCKED, 100000, 500000, 10000",
            "NULL, 200000, NULL, NULL, ACTIVE, 200000, 500000, 10000",
            "NULL, NULL, 600000, NULL, ACTIVE, 100000, 600000, 10000",
            "NULL, NULL, NULL, 0, ACTIVE, 100000, 500000, 0",
            "INACTIVE, 200000, 600000, 5000, INACTIVE, 200000, 600000, 5000"
    }, nullValues = "NULL")
    void shouldPreserveIdentityAndPatchProvidedFieldsWhenUpdatingCard(
            CardStatus status, BigDecimal dailyLimit, BigDecimal monthlyLimit, BigDecimal balance,
            CardStatus expectedStatus, BigDecimal expectedDaily, BigDecimal expectedMonthly, BigDecimal expectedBalance) {
        //Arrange
        Card original = activeCard();
        Card expected = cardWithData(expectedStatus, expectedDaily, expectedMonthly, expectedBalance);
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(original));
        when(cardRepository.update(expected)).thenReturn(expected);

        //Act
        Card updated = cardService.patchCard(PAN, status, dailyLimit, monthlyLimit, balance);

        //Assert
        assertThat(updated).isEqualTo(expected);
        assertThat(original).isEqualTo(activeCard());
        verify(cardRepository).update(expected);
        verify(eventNotifier).notifyListeners(new CardServicePatchEvent(PAN));
    }

    @ParameterizedTest(name = "daily={0}, monthly={1}")
    @CsvSource({"-1, 500000", "100000, -1", "500001, 500000"})
    void shouldRejectWithoutSavingOrNotifyingWhenLimitsAreInvalid(BigDecimal dailyLimit, BigDecimal monthlyLimit) {
        //Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));

        //Act
        Throwable failure = catchThrowable(() -> cardService.patchCard(PAN, null, dailyLimit, monthlyLimit, null));

        //Assert
        assertThat(failure).isInstanceOf(IllegalArgumentException.class);
        verify(cardRepository, never()).update(any(Card.class));
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldRejectPatchWhenCardDoesNotExist() {
        //Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.empty());

        //Act
        Throwable failure = catchThrowable(() -> cardService.patchCard(PAN, CardStatus.BLOCKED, null, null, null));

        //Assert
        assertThat(failure).isInstanceOf(CardNotFoundException.class);
        verify(cardRepository, never()).update(any(Card.class));
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldNotNotifyWhenSavingPatchedCardFails() {
        //Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(activeCard()));
        var databaseFailure = new DataAccessResourceFailureException("Storage unavailable");
        when(cardRepository.update(any(Card.class))).thenThrow(databaseFailure);

        //Act
        Throwable failure = catchThrowable(() -> cardService.patchCard(PAN, CardStatus.BLOCKED, null, null, null));

        //Assert
        assertThat(failure).isExactlyInstanceOf(DataAccessResourceFailureException.class)
                .hasMessage(databaseFailure.getMessage());
        verifyNoInteractions(eventNotifier);
    }

    @ParameterizedTest(name = "originalStatus={0}")
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "DELETED")
    void shouldSoftDeleteCardAndNotifyWhenCardIsNotDeleted(CardStatus status) {
        //Arrange
        Card original = cardWithData(status, DAILY_LIMIT, MONTHLY_LIMIT, BALANCE);
        Card deleted = cardWithData(CardStatus.DELETED, DAILY_LIMIT, MONTHLY_LIMIT, BALANCE);
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(original));

        //Act
        cardService.deleteCard(PAN);

        //Assert
        verify(cardRepository).update(deleted);
        verify(eventNotifier).notifyListeners(new CardServiceDeletionEvent(PAN));
        assertThat(original.status()).isEqualTo(status);
    }

    @Test
    void shouldRejectDeletionWhenCardDoesNotExist() {
        //Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.empty());

        //Act
        Throwable failure = catchThrowable(() -> cardService.deleteCard(PAN));

        //Assert
        assertThat(failure).isInstanceOf(CardNotFoundException.class);
        verify(cardRepository, never()).update(any(Card.class));
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldRejectDeletionWhenCardIsAlreadyDeleted() {
        //Arrange
        Card deleted = cardWithData(CardStatus.DELETED, DAILY_LIMIT, MONTHLY_LIMIT, BALANCE);
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(deleted));

        //Act
        Throwable failure = catchThrowable(() -> cardService.deleteCard(PAN));

        //Assert
        assertThat(failure).isInstanceOf(IllegalStateException.class).hasMessage("Card already deleted");
        verify(cardRepository, never()).update(any(Card.class));
        verifyNoInteractions(eventNotifier);
    }

    @ParameterizedTest(name = "count={0}")
    @ValueSource(longs = {0L, 1L, 100L})
    void shouldPublishCurrentCardCountWhenCountIsRefreshed(long expectedCount) {
        //Arrange
        when(cardRepository.countAllCards()).thenReturn(expectedCount);

        //Act
        long count = cardService.countAllCardsAndUpdate();

        //Assert
        assertThat(count).isEqualTo(expectedCount);
        verify(eventNotifier).notifyListeners(new CardsAmountUpdatedEvent(expectedCount));
    }

    @Test
    void shouldUseAllProvidedFiltersWhenCountingCards() {
        //Arrange
        when(cardRepository.countCardsFiltered(CardStatus.ACTIVE, BIN, ISSUER_ID, CREATED_AT, END_DATE)).thenReturn(2L);

        //Act
        long count = cardService.countCardsFiltered(CardStatus.ACTIVE, BIN, ISSUER_ID, CREATED_AT, END_DATE);

        //Assert
        assertThat(count).isEqualTo(2);
    }

    @Test
    void shouldUpdateAllMatchingCardsWhenSelectingByBins() {
        //Arrange
        List<String> bins = List.of(BIN);
        Card first = activeCard();
        Card second = card(SECOND_CARD_ID, SECOND_PAN, CardStatus.ACTIVE, DAILY_LIMIT, MONTHLY_LIMIT, BALANCE);
        when(cardRepository.findCardsByBinsForUpdate(bins)).thenReturn(List.of(first, second));

        //Act
        int updated = cardService.bulkUpdateStatus(bins, null, CardStatus.BLOCKED);

        //Assert
        assertThat(updated).isEqualTo(2);
        verify(cardRepository).update(cardWithData(CardStatus.BLOCKED, DAILY_LIMIT, MONTHLY_LIMIT, BALANCE));
        verify(cardRepository).update(
                card(SECOND_CARD_ID, SECOND_PAN, CardStatus.BLOCKED, DAILY_LIMIT, MONTHLY_LIMIT, BALANCE));
        assertThat(first.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(second.status()).isEqualTo(CardStatus.ACTIVE);
    }

    @ParameterizedTest(name = "newStatus={0}")
    @EnumSource(CardStatus.class)
    void shouldUpdateSelectedCardWhenSelectingByPans(CardStatus status) {
        //Arrange
        List<String> pans = List.of(PAN);
        when(cardRepository.findCardsByPansForUpdate(pans)).thenReturn(List.of(activeCard()));

        //Act
        int updated = cardService.bulkUpdateStatus(null, pans, status);

        //Assert
        assertThat(updated).isEqualTo(1);
        verify(cardRepository).update(cardWithData(status, DAILY_LIMIT, MONTHLY_LIMIT, BALANCE));
    }

    @Test
    void shouldReturnZeroWithoutSavingWhenBulkSelectionIsEmpty() {
        //Arrange
        List<String> bins = List.of(BIN);
        when(cardRepository.findCardsByBinsForUpdate(bins)).thenReturn(List.of());

        //Act
        int updated = cardService.bulkUpdateStatus(bins, null, CardStatus.BLOCKED);

        //Assert
        assertThat(updated).isZero();
        verify(cardRepository, never()).update(any(Card.class));
    }

    @ParameterizedTest(name = "bins={0}, pans={1}")
    @MethodSource("invalidBulkSelectors")
    void shouldRejectWithoutSavingWhenBulkSelectorsAreInvalid(List<String> bins, List<String> pans, String message) {
        //Arrange
        CardStatus newStatus = CardStatus.BLOCKED;

        //Act
        Throwable failure = catchThrowable(() -> cardService.bulkUpdateStatus(bins, pans, newStatus));

        //Assert
        assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        verify(cardRepository, never()).update(any(Card.class));
        verifyNoInteractions(eventNotifier);
    }

    private static Stream<Arguments> invalidBulkSelectors() {
        return Stream.of(
                Arguments.of(null, null, "Either bin or pans must be provided"),
                Arguments.of(List.of(BIN), List.of(PAN), "Only one of bin or pans must be provided"));
    }

    private Card activeCard() {
        return cardWithData(CardStatus.ACTIVE, DAILY_LIMIT, MONTHLY_LIMIT, BALANCE);
    }

    private Card cardWithData(CardStatus status, BigDecimal dailyLimit, BigDecimal monthlyLimit, BigDecimal balance) {
        return card(CARD_ID, PAN, status, dailyLimit, monthlyLimit, balance);
    }

    private Card card(UUID id, String pan, CardStatus status,
                      BigDecimal dailyLimit, BigDecimal monthlyLimit, BigDecimal balance) {
        return new Card(id, pan, BIN, "IVAN IVANOV", YearMonth.of(2029, 1), status, "643",
                dailyLimit, monthlyLimit, balance, ISSUER_ID, CREATED_AT);
    }
}
