package com.processing.cardmanagement.models;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class CardTest {

    private static final String PAN = "4000003458730237";
    private static final String SECOND_PAN = "4000000000000002";
    private static final String RRN = "603100001000";
    private static final UUID CARD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RESERVATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("1000");

    @ParameterizedTest(name = "daily={0}, monthly={1}")
    @CsvSource({"0, 0", "0, 1", "999, 1000", "1000, 1000"})
    void shouldAcceptLimitsWhenNonNegativeAndNotAboveMonthlyLimit(BigDecimal dailyLimit, BigDecimal monthlyLimit) {
        //Arrange
        Card original = activeCard();

        //Act
        Card updated = original.withData(CardStatus.BLOCKED, dailyLimit, monthlyLimit, BigDecimal.ZERO);

        //Assert
        assertThat(updated.status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(updated.dailyLimit()).isEqualByComparingTo(dailyLimit);
        assertThat(updated.monthlyLimit()).isEqualByComparingTo(monthlyLimit);
        assertThat(updated.availableBalance()).isZero();
        assertThat(updated).usingRecursiveComparison()
                .ignoringFields("status", "dailyLimit", "monthlyLimit", "availableBalance").isEqualTo(original);
        assertThat(original).isEqualTo(activeCard());
    }

    @ParameterizedTest(name = "daily={0}, monthly={1}")
    @CsvSource({
            "-1, 1000, Limit can not contain negative values",
            "1000, -1, Limit can not contain negative values",
            "1001, 1000, Daily limit can not be larger than monthly limit"
    })
    void shouldRejectLimitsWhenNegativeOrDailyExceedsMonthly(BigDecimal dailyLimit, BigDecimal monthlyLimit, String message) {
        //Arrange
        Card original = activeCard();

        //Act
        Throwable failure = catchThrowable(() ->
                original.withData(CardStatus.ACTIVE, dailyLimit, monthlyLimit, BigDecimal.ZERO));

        //Assert
        assertThat(failure).isInstanceOf(IllegalArgumentException.class).hasMessage(message);
        assertThat(original).isEqualTo(activeCard());
    }

    @ParameterizedTest(name = "reservationPan={0}")
    @NullSource
    @ValueSource(strings = SECOND_PAN)
    void shouldRejectReservationWhenItBelongsToAnotherCard(String reservationPan) {
        //Arrange
        Card original = activeCard();
        Reservation reservation = new Reservation(
                RESERVATION_ID, reservationPan, AMOUNT, RRN, ReservationStatus.RESERVED, CREATED_AT, CREATED_AT);

        //Act
        Throwable failure = catchThrowable(() -> original.withReservation(reservation));

        //Assert
        assertThat(failure).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Reservation PAN number and card PAN number differ");
        assertThat(original).isEqualTo(activeCard());
    }

    @ParameterizedTest(name = "rollbackPan={0}")
    @NullSource
    @ValueSource(strings = SECOND_PAN)
    void shouldRejectRollbackWhenItBelongsToAnotherCard(String rollbackPan) {
        //Arrange
        Card original = activeCard();
        ReservationRollback rollback = new ReservationRollback(
                UUID.fromString("00000000-0000-0000-0000-000000000003"), RESERVATION_ID, rollbackPan, AMOUNT, RRN, CREATED_AT);

        //Act
        Throwable failure = catchThrowable(() -> original.withRollback(rollback));

        //Assert
        assertThat(failure).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Reservation PAN number and card PAN number differ");
        assertThat(original).isEqualTo(activeCard());
    }

    @Test
    void shouldCreateNewIdentityWhenCopyingCardWithAnotherPan() {
        //Arrange
        Card original = activeCard();

        //Act
        Card copy = original.copyWithPan(SECOND_PAN);

        //Assert
        assertThat(copy.id()).isNotNull().isNotEqualTo(CARD_ID);
        assertThat(copy.pan()).isEqualTo(SECOND_PAN);
        assertThat(copy.createdAt()).isNotNull();
        assertThat(copy).usingRecursiveComparison().ignoringFields("id", "pan", "createdAt").isEqualTo(original);
        assertThat(original).isEqualTo(activeCard());
    }

    private Card activeCard() {
        return new Card(CARD_ID, PAN, "400000", "IVAN IVANOV", YearMonth.of(2029, 1), CardStatus.ACTIVE, "643",
                new BigDecimal("100000"), new BigDecimal("500000"), new BigDecimal("10000"), "ISS001", CREATED_AT);
    }
}
