package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.events.CardServiceCreationEvent;
import com.processing.cardmanagement.exceptions.BinNotFoundException;
import com.processing.cardmanagement.exceptions.MassiveCardCreationCollisionException;
import com.processing.cardmanagement.exceptions.OutOfRetriesException;
import com.processing.cardmanagement.exceptions.PanCollisionException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardDraft;
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
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardServiceImplCreationTest {

    private static final String BIN = "400000";
    private static final String PAN = "4000003458730237";
    private static final String SECOND_PAN = "4000000000000002";
    private static final String RETRIED_PAN = "4000001234567899";
    private static final String SECOND_RETRIED_PAN = "4000000000000010";
    private static final String ISSUER_ID = "ISS001";
    private static final String CARDHOLDER = "IVAN IVANOV";
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
    @Captor
    private ArgumentCaptor<List<Card>> batchCaptor;

    private CardServiceImpl cardService;

    @BeforeEach
    void setUp() {
        cardService = new CardServiceImpl(
                cardRepository, reservationRepository, rollbackRepository, settings, defaults, panGenerator,
                eventNotifier, binIssuerService, new RetryServiceImpl(), new DirectTransactionRunner());
    }

    @Test
    void shouldCreateActiveCardWhenPanIsGeneratedAndIssuerIsResolved() {
        //Arrange
        stubSingleCardCreation();
        when(cardRepository.create(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));
        YearMonth earliestExpiry = YearMonth.now().plusYears(3);
        ArgumentCaptor<Card> saved = ArgumentCaptor.forClass(Card.class);

        //Act
        Card card = cardService.createCard(BIN, CARDHOLDER, "643", DAILY_LIMIT, MONTHLY_LIMIT, BALANCE);

        //Assert
        verify(cardRepository).create(saved.capture());
        assertThat(card).isEqualTo(saved.getValue());
        assertThat(card.id()).isNotNull();
        assertThat(card.pan()).isEqualTo(PAN);
        assertThat(card.bin()).isEqualTo(BIN);
        assertThat(card.cardholderName()).isEqualTo(CARDHOLDER);
        assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(card.currencyCode()).isEqualTo("643");
        assertThat(card.dailyLimit()).isEqualByComparingTo(DAILY_LIMIT);
        assertThat(card.monthlyLimit()).isEqualByComparingTo(MONTHLY_LIMIT);
        assertThat(card.availableBalance()).isEqualByComparingTo(BALANCE);
        assertThat(card.issuerId()).isEqualTo(ISSUER_ID);
        assertThat(card.expiryDate()).isBetween(earliestExpiry, YearMonth.now().plusYears(3));
        assertThat(card.createdAt()).isNotNull();
        verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(1));
    }

    @Test
    void shouldGenerateAnotherPanWhenFirstCardCreationCollides() {
        //Arrange
        stubSingleCardCreation();
        when(panGenerator.generatePan(BIN)).thenReturn(PAN, SECOND_PAN);
        when(cardRepository.create(any(Card.class)))
                .thenThrow(new PanCollisionException(PAN))
                .thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<Card> attempts = ArgumentCaptor.forClass(Card.class);

        //Act
        Card card = cardService.createCard(BIN, CARDHOLDER, "643", DAILY_LIMIT, MONTHLY_LIMIT, BALANCE);

        //Assert
        assertThat(card.pan()).isEqualTo(SECOND_PAN);
        verify(cardRepository, times(2)).create(attempts.capture());
        assertThat(attempts.getAllValues()).extracting(Card::pan).containsExactly(PAN, SECOND_PAN);
        verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(1));
    }

    @ParameterizedTest(name = "maxRetries={0}")
    @ValueSource(ints = {0, 1, 2})
    void shouldStopWithoutCreationEventWhenAllPanAttemptsCollide(int maxRetries) {
        //Arrange
        stubSingleCardCreation();
        when(settings.maxCardCreationRetries()).thenReturn(maxRetries);
        PanCollisionException collision = new PanCollisionException(PAN);
        when(cardRepository.create(any(Card.class))).thenThrow(collision);

        //Act
        Throwable failure = catchThrowable(() ->
                cardService.createCard(BIN, CARDHOLDER, "643", DAILY_LIMIT, MONTHLY_LIMIT, BALANCE));

        //Assert
        assertThat(failure).isInstanceOf(OutOfRetriesException.class);
        assertThat(((OutOfRetriesException) failure).getReason())
                .isInstanceOf(PanCollisionException.class)
                .hasMessage(collision.getMessage());
        verify(cardRepository, times(maxRetries + 1)).create(any(Card.class));
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldRejectWithoutSavingWhenBinIsUnknown() {
        //Arrange
        when(binIssuerService.getIssuerId(BIN)).thenThrow(new BinNotFoundException(BIN));

        //Act
        Throwable failure = catchThrowable(() ->
                cardService.createCard(BIN, CARDHOLDER, "643", DAILY_LIMIT, MONTHLY_LIMIT, BALANCE));

        //Assert
        assertThat(failure).isInstanceOf(BinNotFoundException.class);
        verify(cardRepository, never()).create(any(Card.class));
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldSaveDraftFieldsAndNotifyCountWhenBatchCreationSucceeds() {
        //Arrange
        List<CardDraft> drafts = twoCardDrafts();
        stubBatchGeneration();
        when(cardRepository.createAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        //Act
        List<Card> cards = cardService.createCards(drafts);

        //Assert
        verify(cardRepository).createAll(batchCaptor.capture());
        assertThat(cards).containsExactlyElementsOf(batchCaptor.getValue());
        assertThat(cards).extracting(Card::pan).containsExactly(PAN, SECOND_PAN);
        assertThat(cards).extracting(Card::cardholderName).containsExactly(CARDHOLDER, "PETR PETROV");
        assertThat(cards).extracting(Card::status).containsExactly(CardStatus.ACTIVE, CardStatus.BLOCKED);
        assertThat(cards).extracting(Card::issuerId).containsExactly(ISSUER_ID, ISSUER_ID);
        assertThat(cards).extracting(Card::availableBalance).containsExactly(BALANCE, BALANCE);
        verify(cardRepository, never()).create(any(Card.class));
        verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(2));
    }

    @Test
    void shouldSaveCardsIndividuallyWithFreshPansWhenBatchCollides() {
        //Arrange
        List<CardDraft> drafts = twoCardDrafts();
        stubBatchGeneration();
        when(settings.maxCardCreationRetries()).thenReturn(2);
        when(panGenerator.generatePan(BIN)).thenReturn(PAN, SECOND_PAN, RETRIED_PAN, SECOND_RETRIED_PAN);
        when(cardRepository.createAll(anyList())).thenThrow(new MassiveCardCreationCollisionException());
        when(cardRepository.create(any(Card.class))).thenAnswer(invocation -> invocation.getArgument(0));
        ArgumentCaptor<Card> saved = ArgumentCaptor.forClass(Card.class);

        //Act
        List<Card> cards = cardService.createCards(drafts);

        //Assert
        verify(cardRepository).createAll(batchCaptor.capture());
        assertThat(batchCaptor.getValue()).extracting(Card::pan).containsExactly(PAN, SECOND_PAN);
        verify(cardRepository, times(2)).create(saved.capture());
        assertThat(cards).containsExactlyElementsOf(saved.getAllValues());
        assertThat(cards).extracting(Card::pan).containsExactly(RETRIED_PAN, SECOND_RETRIED_PAN);
        assertThat(cards).extracting(Card::cardholderName).containsExactly(CARDHOLDER, "PETR PETROV");
        assertThat(cards).extracting(Card::status).containsExactly(CardStatus.ACTIVE, CardStatus.BLOCKED);
        verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(2));
    }

    @Test
    void shouldReportZeroCreatedCardsWhenBatchIsEmpty() {
        //Arrange
        List<CardDraft> drafts = List.of();
        when(cardRepository.createAll(List.of())).thenReturn(List.of());

        //Act
        List<Card> cards = cardService.createCards(drafts);

        //Assert
        assertThat(cards).isEmpty();
        verify(cardRepository).createAll(List.of());
        verify(cardRepository, never()).create(any(Card.class));
        verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(0));
    }

    private void stubSingleCardCreation() {
        when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
        when(settings.cardValidityPeriod()).thenReturn(3);
        when(settings.maxCardCreationRetries()).thenReturn(2);
        when(panGenerator.generatePan(BIN)).thenReturn(PAN);
    }

    private void stubBatchGeneration() {
        when(binIssuerService.getIssuerId(BIN)).thenReturn(ISSUER_ID);
        when(settings.cardValidityPeriod()).thenReturn(3);
        when(panGenerator.generatePan(BIN)).thenReturn(PAN, SECOND_PAN);
    }

    private List<CardDraft> twoCardDrafts() {
        return List.of(
                new CardDraft(BIN, CARDHOLDER, CardStatus.ACTIVE, "643", DAILY_LIMIT, MONTHLY_LIMIT, BALANCE),
                new CardDraft(BIN, "PETR PETROV", CardStatus.BLOCKED, "643", DAILY_LIMIT, MONTHLY_LIMIT, BALANCE));
    }
}
