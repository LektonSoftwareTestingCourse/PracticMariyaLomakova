package com.processing.cardmanagement.repositories;

import com.processing.cardmanagement.exceptions.PanCollisionException;
import com.processing.cardmanagement.mappers.CardExpiryDateMapper;
import com.processing.cardmanagement.mappers.CardPersistenceMapperImpl;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CardRepositoryPersistenceAdapter.class, CardExpiryDateMapper.class, CardPersistenceMapperImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class CardRepositoryIT {

    private static final AtomicLong PAN_SEQUENCE = new AtomicLong();

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private CardRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void clearCards() {
        jdbc.execute("TRUNCATE TABLE cards RESTART IDENTITY CASCADE");
    }

    @Test
    void roundTripsCardThroughMigratedDatabase() {
        Card card = card(uniquePan(), CardStatus.ACTIVE);

        transactions.executeWithoutResult(status -> repository.create(card));

        Card persisted = transactions.execute(status -> repository.findByPan(card.pan()).orElseThrow());
        assertThat(persisted).usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .isEqualTo(card);
    }

    @Test
    void rejectsDuplicateActivePanAndKeepsOriginalCard() {
        String pan = uniquePan();
        Card original = card(pan, CardStatus.ACTIVE);
        transactions.executeWithoutResult(status -> repository.create(original));

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> repository.create(card(pan, CardStatus.ACTIVE))))
                .isInstanceOf(PanCollisionException.class);

        assertThat(transactions.execute(status -> repository.findByPan(pan).orElseThrow()).id())
                .isEqualTo(original.id());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cards WHERE pan = ?", Long.class, pan)).isEqualTo(1L);
    }

    @Test
    void reusesPanOfDeletedCardWithoutRemovingOldRow() {
        String pan = uniquePan();
        Card deleted = card(pan, CardStatus.DELETED);
        transactions.executeWithoutResult(status -> repository.create(deleted));
        Card replacement = card(pan, CardStatus.ACTIVE);

        transactions.executeWithoutResult(status -> repository.create(replacement));

        assertThat(transactions.execute(status -> repository.findByPan(pan).orElseThrow()).id())
                .isEqualTo(replacement.id());
        assertThat(jdbc.queryForList("SELECT status FROM cards WHERE pan = ?", String.class, pan))
                .containsExactlyInAnyOrder("DELETED", "ACTIVE");
    }

    private static String uniquePan() {
        return String.format(Locale.ROOT, "400000%010d", PAN_SEQUENCE.incrementAndGet());
    }

    private static Card card(String pan, CardStatus status) {
        return new Card(UUID.randomUUID(), pan, "400000", "TEST CARDHOLDER", YearMonth.of(2030, 2), status, "643",
                BigDecimal.valueOf(10_001), BigDecimal.valueOf(50_002), BigDecimal.valueOf(100_003),
                "ISS001", Instant.parse("2026-01-31T23:59:59.123456Z"));
    }
}
