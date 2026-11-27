package com.processing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.GetResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startable;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers
class SwitchAuthorizationChainIT {

    private static final Network NETWORK = Network.newNetwork();
    private static final String USER = "smp";
    private static final String PASSWORD = "smp";
    private static final int HTTP_PORT = 8080;
    private static final String EXCHANGE = "smp.transactions";
    private static final String ROUTING_KEY = "transaction.log";
    private static final String MAIN_QUEUE = "transaction-log";
    private static final Duration ASYNC_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    @Container
    private static final PostgreSQLContainer<?> CARD_DB = postgres("card-db");

    @Container
    private static final PostgreSQLContainer<?> AUTH_DB = postgres("auth-db");

    @Container
    private static final PostgreSQLContainer<?> LOGGER_DB = postgres("logger-db");

    @Container
    private static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management")
            .withUser(USER, PASSWORD)
            .withPermission("/", USER, ".*", ".*", ".*")
            .withNetwork(NETWORK)
            .withNetworkAliases("rabbit");

    @Container
    private static final GenericContainer<?> WIREMOCK = new GenericContainer<>("wiremock/wiremock:3.12.1")
            .withNetwork(NETWORK)
            .withNetworkAliases("wiremock")
            .withExposedPorts(HTTP_PORT)
            .waitingFor(Wait.forHttp("/__admin/mappings").forStatusCode(200));

    @Container
    private static final GenericContainer<?> CARDS = service("card-management", CARD_DB, RABBIT)
            .withEnv("POSTGRES_HOST", "card-db")
            .withEnv("METRICS_SCHEDULER_ENABLED", "false");

    @Container
    private static final GenericContainer<?> LOGGER = service("transaction-logger", LOGGER_DB, RABBIT)
            .withEnv("POSTGRES_HOST", "logger-db");

    @Container
    private static final GenericContainer<?> AUTHORIZATION = service("authorization", AUTH_DB, CARDS, WIREMOCK)
            .withEnv("POSTGRES_HOST", "auth-db")
            .withEnv("CARD_MGMT_URL", "http://card-management:8080")
            .withEnv("BIN_LOOKUP_URL", "http://wiremock:8080");

    @Container
    private static final GenericContainer<?> SWITCH = service("switch", AUTHORIZATION, LOGGER, RABBIT, WIREMOCK)
            .withEnv("AUTH_URL", "http://authorization:8080")
            .withEnv("LOGGER_URL", "http://transaction-logger:8080")
            .withEnv("MERCHANT_URL", "http://wiremock:8080");

    @BeforeEach
    void clearState() throws Exception {
        database(CARD_DB).execute("""
                TRUNCATE TABLE cards, reservations, reservation_rollbacks, outbox_event RESTART IDENTITY CASCADE
                """);
        database(AUTH_DB).execute("TRUNCATE TABLE limit_usage RESTART IDENTITY CASCADE");
        database(LOGGER_DB).execute("TRUNCATE TABLE transactions RESTART IDENTITY CASCADE");
        try (Connection connection = rabbitConnection(); var channel = connection.createChannel()) {
            await("Logger subscribed before the authorization request").atMost(ASYNC_TIMEOUT).pollInterval(POLL_INTERVAL)
                    .untilAsserted(() -> assertThat(channel.queueDeclarePassive(MAIN_QUEUE).getConsumerCount()).isPositive());
            channel.queuePurge(MAIN_QUEUE);
        }
        client(WIREMOCK).post().uri("/__admin/reset").retrieve().toBodilessEntity();
    }

    @Test
    void approvesAndCommitsLimitReservationAndLogForSameTransaction() throws Exception {
        String stan = String.format(Locale.ROOT, "%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        BigDecimal amount = BigDecimal.valueOf(725);
        BigDecimal fee = BigDecimal.valueOf(17);
        Instant sentAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        CardModel card = createCard();
        AuthorizationRequest request = new AuthorizationRequest("0100", stan, card.pan(), "000000", amount,
                "643", sentAt, "TERM0001", "POS", "MERCH0000000001", "5411", "ACQ001", "ISS001");
        Map<String, ?> binRequest = Map.of("method", "GET", "url", "/api/bin/" + card.bin());
        Map<String, ?> feeRequest = Map.of("method", "POST", "url", "/api/simulator/merchant/fee",
                "bodyPatterns", List.of(jsonField("stan", stan), jsonField("pan", card.pan())));
        stub(binRequest, Map.of("issuerId", "ISS001", "bankName", "Test Bank 1", "countryCode", "RU"));
        stub(feeRequest, Map.of("acquirerFee", fee));

        try (Connection connection = rabbitConnection(); var observer = connection.createChannel()) {
            String queue = observer.queueDeclare().getQueue();
            observer.queueBind(queue, EXCHANGE, ROUTING_KEY);

            ResponseEntity<AuthorizationResponse> response = client(SWITCH).post().uri("/api/internal/route")
                    .body(request).retrieve().toEntity(AuthorizationResponse.class);

            assertThat(response.getStatusCode().value()).isEqualTo(200);
            AuthorizationResponse authorization = response.getBody();
            assertThat(authorization).isNotNull();
            assertThat(authorization.status()).isEqualTo(AuthorizationResponse.STATUS_APPROVED);
            assertThat(authorization.responseCode()).isEqualTo(AuthorizationResponse.CODE_APPROVED);
            assertThat(authorization.stan()).isEqualTo(stan);
            String rrn = authorization.rrn();
            assertThat(rrn).matches("\\d{12}");

            await("Switch published authorization " + rrn).atMost(ASYNC_TIMEOUT).pollInterval(POLL_INTERVAL)
                    .untilAsserted(() -> assertThat(observer.messageCount(queue)).isEqualTo(1));
            TransactionRequest event = transactionEvent(observer.basicGet(queue, true));
            assertThat(event.rrn()).isEqualTo(rrn);
            assertThat(event.stan()).isEqualTo(stan);
            assertThat(event.status()).isEqualTo(TransactionStatus.APPROVED);
            assertThat(event.amount()).isEqualByComparingTo(amount);
            assertThat(event.acquiringFee()).isEqualByComparingTo(fee);

            assertLoggerRow(event);
            assertAuthorizationUsage(card.pan(), sentAt, amount);
            assertCardReservation(card.pan(), rrn, amount);
        }
        assertInvokedOnce(binRequest);
        assertInvokedOnce(feeRequest);
    }

    private static CardModel createCard() {
        ResponseEntity<CardModel> response = client(CARDS).post().uri("/api/cards")
                .body(Map.of("bin", "400000", "cardholderName", "CHAIN TEST", "currencyCode", "643",
                        "dailyLimit", 10_000, "monthlyLimit", 100_000, "initialBalance", 50_000))
                .retrieve().toEntity(CardModel.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isNotNull();
        return response.getBody();
    }

    private static void assertLoggerRow(TransactionRequest event) {
        JdbcTemplate jdbc = database(LOGGER_DB);
        await("Logger committed transaction " + event.rrn()).atMost(ASYNC_TIMEOUT).pollInterval(POLL_INTERVAL)
                .untilAsserted(() -> assertThat(jdbc.queryForList("""
                        SELECT id, stan, status, amount, acquiring_fee FROM transactions WHERE rrn = ?
                        """, event.rrn())).singleElement().satisfies(row -> {
                    assertThat(row.get("id")).isEqualTo(event.id());
                    assertThat(row.get("stan")).isEqualTo(event.stan());
                    assertThat(row.get("status")).isEqualTo(event.status().name());
                    assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo(event.amount());
                    assertThat((BigDecimal) row.get("acquiring_fee")).isEqualByComparingTo(event.acquiringFee());
                }));
    }

    private static void assertAuthorizationUsage(String pan, Instant sentAt, BigDecimal amount) {
        assertThat(database(AUTH_DB).queryForList("""
                SELECT daily_amount, monthly_amount FROM limit_usage WHERE pan = ? AND usage_date = ?
                """, pan, sentAt.atOffset(ZoneOffset.UTC).toLocalDate())).singleElement().satisfies(row -> {
            assertThat((BigDecimal) row.get("daily_amount")).isEqualByComparingTo(amount);
            assertThat((BigDecimal) row.get("monthly_amount")).isEqualByComparingTo(amount);
        });
    }

    private static void assertCardReservation(String pan, String rrn, BigDecimal amount) {
        assertThat(database(CARD_DB).queryForList("""
                SELECT status, reservation_amount FROM reservations WHERE rrn = ? AND pan = ?
                """, rrn, pan)).singleElement().satisfies(row -> {
            assertThat(row.get("status")).isEqualTo("RESERVED");
            assertThat((BigDecimal) row.get("reservation_amount")).isEqualByComparingTo(amount);
        });
    }

    private static TransactionRequest transactionEvent(GetResponse delivery) {
        assertThat(delivery).isNotNull();
        MessageProperties properties = new MessageProperties();
        properties.setContentType(delivery.getProps().getContentType());
        SimpleMessageConverter converter = new SimpleMessageConverter();
        converter.addAllowedListPatterns("com.processing.common.dto.transactionlogger.*",
                "java.util.UUID", "java.math.*", "java.time.*", "java.lang.*");
        return (TransactionRequest) converter.fromMessage(new Message(delivery.getBody(), properties));
    }

    private static Map<String, ?> jsonField(String field, String value) {
        return Map.of("matchesJsonPath", Map.of("expression", "$." + field, "equalTo", value));
    }

    private static void stub(Map<String, ?> request, Map<String, ?> responseBody) {
        client(WIREMOCK).post().uri("/__admin/mappings").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("request", request, "response", Map.of("status", 200, "jsonBody", responseBody,
                        "headers", Map.of("Content-Type", "application/json"))))
                .retrieve().toBodilessEntity();
    }

    private static void assertInvokedOnce(Map<String, ?> expectedRequest) {
        JsonNode result = client(WIREMOCK).post().uri("/__admin/requests/count").contentType(MediaType.APPLICATION_JSON)
                .body(expectedRequest).retrieve().body(JsonNode.class);
        assertThat(result).isNotNull();
        assertThat(result.path("count").asInt()).isEqualTo(1);
    }

    private static Connection rabbitConnection() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(RABBIT.getHost());
        factory.setPort(RABBIT.getAmqpPort());
        factory.setUsername(USER);
        factory.setPassword(PASSWORD);
        return factory.newConnection();
    }

    private static JdbcTemplate database(PostgreSQLContainer<?> postgres) {
        return new JdbcTemplate(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    }

    private static RestClient client(GenericContainer<?> container) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(ASYNC_TIMEOUT);
        requestFactory.setReadTimeout(ASYNC_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory)
                .baseUrl("http://" + container.getHost() + ":" + container.getMappedPort(HTTP_PORT)).build();
    }

    private static PostgreSQLContainer<?> postgres(String alias) {
        return new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("smp").withUsername(USER)
                .withPassword(PASSWORD).withNetwork(NETWORK).withNetworkAliases(alias);
    }

    private static GenericContainer<?> service(String name, Startable... dependencies) {
        String version = System.getProperty("smp.service.version", "1.0.0");
        Path jar = Path.of("..", name, "target", name + "-" + version + ".jar").toAbsolutePath().normalize();
        ImageFromDockerfile image = new ImageFromDockerfile()
                .withFileFromPath("service.jar", jar)
                .withDockerfileFromBuilder(dockerfile -> dockerfile.from("eclipse-temurin:21-jre-alpine")
                        .copy("service.jar", "/service.jar").entryPoint("java", "-jar", "/service.jar").build());
        return new GenericContainer<>(image).withNetwork(NETWORK).withNetworkAliases(name).dependsOn(dependencies)
                .withEnv("PORT", Integer.toString(HTTP_PORT)).withEnv("SERVER_PORT", Integer.toString(HTTP_PORT))
                .withEnv("POSTGRES_DB", "smp").withEnv("POSTGRES_USER", USER).withEnv("POSTGRES_PASSWORD", PASSWORD)
                .withEnv("SPRING_RABBITMQ_HOST", "rabbit").withEnv("SPRING_RABBITMQ_USERNAME", USER)
                .withEnv("SPRING_RABBITMQ_PASSWORD", PASSWORD)
                .withEnv("JAVA_TOOL_OPTIONS", "-Xms32m -Xmx192m -XX:MaxMetaspaceSize=160m")
                .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("containers." + name)))
                .withExposedPorts(HTTP_PORT)
                .waitingFor(Wait.forHttp("/health").forStatusCode(200).withStartupTimeout(Duration.ofSeconds(90)));
    }
}
