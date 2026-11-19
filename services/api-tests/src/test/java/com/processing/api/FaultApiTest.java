package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import com.processing.api.support.Stack;
import io.restassured.response.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@Tag("fault")
class FaultApiTest {
    @ParameterizedTest(name = "{0}: {2} {3}")
    @MethodSource("unavailableRoutes")
    void shouldIdentifyUnavailableDownstreamWhenBusinessRouteCannotConnect(
            String container, String serviceName, String method, String path) throws Exception {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Map<String, Object> body = Data.authorization(pan, 1000);
        body.put("rrn", "000000" + Data.stan());
        Map<String, Map<String, Object>> requestBodies = Map.of(
                "/api/cards", Data.cardBody(10000, 1000, 5000),
                "/api/cards/generate", Map.of("count", 1, "bins", java.util.List.of("400000")),
                "/api/cards/bulk-update", Map.of("pans", java.util.List.of(pan), "status", "BLOCKED"),
                "/api/cards/{pan}", Map.of("status", "BLOCKED"),
                "/api/simulator/terminal/run", Map.of("count", 1, "scenario", "normal", "tps", 1),
                "/api/simulator/terminal/start-continuous", Map.of("scenario", "normal", "tps", 1),
                "/api/simulator/merchant/run", Map.of("count", 1, "scenario", "grocery", "tps", 1));
        Map<String, Object> requestBody = requestBodies.getOrDefault(path, body);
        String endpoint = path.replace("{pan}", pan);
        Stack.command("stop", container);
        try {
            //When
            Response response = given().spec(Api.request("gateway")).body(requestBody).when().request(method, endpoint);
            //Then
            response.then().statusCode(503).contentType("application/json")
                    .body("error", equalTo("SERVICE_UNAVAILABLE")).body("serviceName", equalTo(serviceName))
                    .body("message", containsString("temporarily unavailable"));
        } finally {
            Stack.command("start", container);
            Stack.ready();
            Stack.gatewayReady(serviceName, pan);
        }
    }

    static Stream<Arguments> unavailableRoutes() {
        return Stream.of(
                Arguments.of("card-management", "cardManagement", "POST", "/api/cards"),
                Arguments.of("card-management", "cardManagement", "GET", "/api/cards/{pan}"),
                Arguments.of("card-management", "cardManagement", "GET", "/api/cards"),
                Arguments.of("card-management", "cardManagement", "PATCH", "/api/cards/{pan}"),
                Arguments.of("card-management", "cardManagement", "DELETE", "/api/cards/{pan}"),
                Arguments.of("card-management", "cardManagement", "POST", "/api/cards/generate"),
                Arguments.of("card-management", "cardManagement", "POST", "/api/cards/{pan}/reserve"),
                Arguments.of("card-management", "cardManagement", "POST", "/api/cards/{pan}/rollback"),
                Arguments.of("card-management", "cardManagement", "POST", "/api/cards/bulk-update"),
                Arguments.of("transaction-logger", "logger", "GET", "/api/transactions/search"),
                Arguments.of("transaction-logger", "logger", "GET", "/api/transactions/export"),
                Arguments.of("transaction-logger", "logger", "GET", "/api/dashboard/stats"),
                Arguments.of("transaction-logger", "logger", "GET", "/api/dashboard/recent"),
                Arguments.of("transaction-logger", "logger", "GET", "/api/dashboard/charts"),
                Arguments.of("switch", "switch", "POST", "/api/transactions"),
                Arguments.of("terminal-simulator", "terminalSimulator", "POST", "/api/simulator/terminal/run"),
                Arguments.of("terminal-simulator", "terminalSimulator", "POST", "/api/simulator/terminal/start-continuous"),
                Arguments.of("terminal-simulator", "terminalSimulator", "POST", "/api/simulator/terminal/stop"),
                Arguments.of("merchant-acquirer", "merchantSimulator", "POST", "/api/simulator/merchant/run"),
                Arguments.of("merchant-acquirer", "merchantSimulator", "POST", "/api/simulator/merchant/fee"));
    }

    @Test
    void shouldBlockLoggerRouteWhenRepeatedDownstreamFailuresOpenCircuit() throws Exception {
        //Given
        given().spec(Api.request("gateway")).when().get("/api/dashboard/stats")
                .then().statusCode(200).body("totalTransactions", greaterThanOrEqualTo(0));
        Stack.command("stop", "transaction-logger");
        try {
            given().spec(Api.request("gateway")).when().get("/api/dashboard/stats")
                    .then().statusCode(503).body("error", equalTo("SERVICE_UNAVAILABLE"))
                    .body("serviceName", equalTo("logger"));
            given().spec(Api.request("gateway")).when().get("/api/dashboard/stats")
                    .then().statusCode(503).body("error", equalTo("SERVICE_UNAVAILABLE"))
                    .body("serviceName", equalTo("logger"));
            given().spec(Api.request("gateway")).when().get("/api/dashboard/stats")
                    .then().statusCode(503).body("error", equalTo("SERVICE_UNAVAILABLE"))
                    .body("serviceName", equalTo("logger"));
            //When
            Response response = given().spec(Api.request("gateway")).when().get("/api/dashboard/stats");
            //Then
            response.then().statusCode(503).header("Retry-After", equalTo("10"))
                    .body("error", equalTo("SERVICE_UNAVAILABLE")).body("serviceName", equalTo("logger"));
        } finally {
            Stack.command("start", "transaction-logger");
            Stack.ready();
            await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofSeconds(1))
                    .ignoreExceptions().untilAsserted(() ->
                            given().spec(Api.request("gateway")).when().get("/api/dashboard/stats")
                                    .then().statusCode(200).body("totalTransactions", greaterThanOrEqualTo(0)));
        }
    }

    @Test
    void shouldRestoreBalanceAndKeepLogAbsentWhenBrokerCannotAcceptTransaction() throws Exception {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Map<String, Object> body = Data.authorization(pan, 1000);
        Stack.command("stop", "rabbitmq");
        try {
            //When
            Response response = given().spec(Api.request("gateway")).body(body).when().post("/api/transactions");
            //Then
            response.then().statusCode(200).body("status", equalTo("DECLINED"))
                    .body("responseCode", equalTo("96")).body("declineReason", equalTo("System error"));
            assertThat(Data.balance(pan).intValueExact(), equalTo(1000000));
        } finally {
            Stack.command("start", "rabbitmq");
        }
        await().during(Duration.ofSeconds(10)).atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                        Data.search(pan).then().statusCode(200).body("total", equalTo(0))
                                .body("transactions", hasSize(0)));
        Stack.ready();
    }

    @Test
    void shouldReturnServiceUnavailableWhenCardServiceIsStopped() throws Exception {
        //Given
        Map<String, Object> body = Data.authorization(Data.createCard().get("pan").toString(), 1000);
        Stack.command("stop", "card-management");
        try {
            //When
            Response response = given().spec(Api.request("authorization")).body(body)
                    .when().post("/api/internal/authorize");
            //Then
            response.then().statusCode(503).body("status", equalTo("DECLINED"))
                    .body("responseCode", equalTo("96")).body("declineReason", equalTo("SERVICE_UNAVAILABLE"));
            given().spec(Api.request("gateway")).when().get("/health").then().statusCode(503)
                    .body("services.cardManagement", equalTo("unavailable"));
        } finally {
            Stack.command("start", "card-management");
            Stack.ready();
        }
    }

    @Test
    void shouldReturnDatabaseFailureWhenLoggerCannotReachStorage() throws Exception {
        //Given
        Map<String, Object> tx = Data.transaction(Data.createCard().get("pan").toString());
        Stack.command("stop", "postgres");
        try {
            //When
            Response response = given().spec(Api.request("logger")).body(tx).when().post("/api/internal/log");
            //Then
            response.then().statusCode(503).body("serviceName", equalTo("transaction-logger"))
                    .body("error", equalTo("Database operation failed")).body("retryAfterMs", equalTo("1000"));
        } finally {
            Stack.command("start", "postgres");
            Stack.ready();
        }
    }
}
