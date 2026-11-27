package com.processing.authorization.repositories;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers
class LimitUsageRepositoryIT {

    private static final AtomicLong PAN_SEQUENCE = new AtomicLong();
    private static final LocalDate DATE = LocalDate.of(2026, 11, 12);

    @Container
    @ServiceConnection
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private LimitUsageRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @BeforeEach
    void clearUsage() {
        jdbc.execute("TRUNCATE TABLE limit_usage RESTART IDENTITY CASCADE");
    }

    @Test
    void insertsFirstUsage() {
        String pan = uniquePan();

        assertThat(upsert(pan, DATE, 40, 100, 500)).isEqualTo(1);

        assertThat(usage(pan)).singleElement().satisfies(row -> assertUsage(row, DATE, 40, 40));
    }

    @Test
    void updatesSameRowAndAccumulatesBothAmounts() {
        String pan = uniquePan();
        assertThat(upsert(pan, DATE, 40, 100, 500)).isEqualTo(1);
        UUID originalId = usage(pan).getFirst().id();

        assertThat(upsert(pan, DATE, 25, 100, 500)).isEqualTo(1);

        assertThat(usage(pan)).singleElement().satisfies(row -> {
            assertThat(row.id()).isEqualTo(originalId);
            assertUsage(row, DATE, 65, 65);
        });
    }

    @Test
    void acceptsExactDailyLimitAndRejectsNextUnitWithoutMutation() {
        String pan = uniquePan();
        assertThat(upsert(pan, DATE, 90, 100, 500)).isEqualTo(1);
        assertThat(upsert(pan, DATE, 10, 100, 500)).isEqualTo(1);
        List<Usage> beforeDecline = usage(pan);

        assertThat(upsert(pan, DATE, 1, 100, 500)).isZero();

        assertThat(beforeDecline).singleElement().satisfies(row -> assertUsage(row, DATE, 100, 100));
        assertThat(usage(pan)).isEqualTo(beforeDecline);
    }

    @Test
    void carriesMonthlyUsageAcrossDatesAndRejectsExcess() {
        String pan = uniquePan();
        assertThat(upsert(pan, DATE, 70, 90, 100)).isEqualTo(1);
        assertThat(upsert(pan, DATE.plusDays(1), 30, 90, 100)).isEqualTo(1);
        List<Usage> beforeDecline = usage(pan);

        assertThat(upsert(pan, DATE.plusDays(1), 1, 90, 100)).isZero();
        assertThat(upsert(pan, DATE.plusDays(2), 1, 90, 100)).isZero();

        assertThat(beforeDecline).satisfiesExactly(
                first -> assertUsage(first, DATE, 70, 70),
                second -> assertUsage(second, DATE.plusDays(1), 30, 100));
        assertThat(usage(pan)).isEqualTo(beforeDecline);
    }

    private int upsert(String pan, LocalDate date, long amount, long dailyLimit, long monthlyLimit) {
        return transactions.execute(status -> repository.upsertLimitUsage(pan, date,
                BigDecimal.valueOf(amount), BigDecimal.valueOf(dailyLimit), BigDecimal.valueOf(monthlyLimit)));
    }

    private List<Usage> usage(String pan) {
        return transactions.execute(status -> jdbc.query("""
                SELECT id, usage_date, daily_amount, monthly_amount
                FROM limit_usage WHERE pan = ? ORDER BY usage_date
                """, (row, index) -> new Usage(row.getObject("id", UUID.class), row.getObject("usage_date", LocalDate.class),
                row.getBigDecimal("daily_amount"), row.getBigDecimal("monthly_amount")), pan));
    }

    private static void assertUsage(Usage row, LocalDate date, long daily, long monthly) {
        assertThat(row.date()).isEqualTo(date);
        assertThat(row.daily()).isEqualByComparingTo(BigDecimal.valueOf(daily));
        assertThat(row.monthly()).isEqualByComparingTo(BigDecimal.valueOf(monthly));
    }

    private static String uniquePan() {
        return String.format(Locale.ROOT, "400001%010d", PAN_SEQUENCE.incrementAndGet());
    }

    private record Usage(UUID id, LocalDate date, BigDecimal daily, BigDecimal monthly) {
    }
}
