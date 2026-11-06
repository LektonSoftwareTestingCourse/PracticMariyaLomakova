package com.processing.cardmanagement.models;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class ReservationTest {

    private static final String PAN = "4000003458730237";
    private static final String RRN = "603100001000";
    private static final UUID RESERVATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ROLLBACK_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final BigDecimal AMOUNT = new BigDecimal("1000");

    @ParameterizedTest(name = "amount={0}")
    @ValueSource(strings = {"1", "999", "1000"})
    void shouldLinkRollbackToOriginalReservationWhenStartingRollback(BigDecimal amount) {
        //Arrange
        Reservation original = reserved(amount);

        //Act
        ReservationRollback rollback = original.startRollback(amount);

        //Assert
        assertThat(rollback.id()).isNotNull();
        assertThat(rollback.reservationId()).isEqualTo(RESERVATION_ID);
        assertThat(rollback.pan()).isEqualTo(PAN);
        assertThat(rollback.rrn()).isEqualTo(RRN);
        assertThat(rollback.rollbackAmount()).isEqualByComparingTo(amount);
        assertThat(original.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void shouldPreserveIdentityAndAmountWhenMarkingReservationRolledBack() {
        //Arrange
        Reservation original = reserved(AMOUNT);
        ReservationRollback rollback = rollbackFor(RESERVATION_ID);

        //Act
        Reservation updated = original.rolledBack(rollback);

        //Assert
        assertThat(updated.status()).isEqualTo(ReservationStatus.ROLLED_BACK);
        assertThat(updated).usingRecursiveComparison().ignoringFields("status", "updatedAt").isEqualTo(original);
        assertThat(updated.updatedAt()).isNotNull();
        assertThat(original.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(original.updatedAt()).isEqualTo(CREATED_AT);
    }

    @ParameterizedTest(name = "foreignReservationId={0}")
    @NullSource
    @ValueSource(strings = "00000000-0000-0000-0000-000000000003")
    void shouldRejectRollbackWhenItBelongsToAnotherReservation(UUID foreignReservationId) {
        //Arrange
        Reservation original = reserved(AMOUNT);
        ReservationRollback rollback = rollbackFor(foreignReservationId);

        //Act
        Throwable failure = catchThrowable(() -> original.rolledBack(rollback));

        //Assert
        assertThat(failure).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Rollback's reservation ID and this ID differ");
        assertThat(original.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    private Reservation reserved(BigDecimal amount) {
        return new Reservation(RESERVATION_ID, PAN, amount, RRN, ReservationStatus.RESERVED, CREATED_AT, CREATED_AT);
    }

    private ReservationRollback rollbackFor(UUID reservationId) {
        return new ReservationRollback(ROLLBACK_ID, reservationId, PAN, AMOUNT, RRN, CREATED_AT);
    }
}
