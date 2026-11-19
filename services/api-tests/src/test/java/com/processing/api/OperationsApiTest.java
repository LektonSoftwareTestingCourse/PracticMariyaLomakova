package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.time.Duration;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.awaitility.Awaitility.await;

class OperationsApiTest {
    @Test
    void shouldExposeHealthWhenActuatorIsRequested() {
        //Given
        String path = "/actuator/health";
        //When
        Response response = given().spec(Api.request("gateway")).when().get(path);
        //Then
        response.then().statusCode(200).body("status", equalTo("UP"));
    }

    @Test
    void shouldExposeInfoWhenActuatorIsRequested() {
        //Given
        String path = "/actuator/info";
        //When
        Response response = given().spec(Api.request("gateway")).when().get(path);
        //Then
        response.then().statusCode(200).body("$", instanceOf(Map.class));
    }

    @Test
    void shouldExposeMetricNamesWhenActuatorIsRequested() {
        //Given
        String path = "/actuator/metrics";
        //When
        Response response = given().spec(Api.request("gateway")).when().get(path);
        //Then
        response.then().statusCode(200).body("names", hasItem("jvm.memory.used"));
    }

    @Test
    void shouldExposePrometheusWhenMetricsAreRequested() {
        //Given
        String path = "/actuator/prometheus";
        //When
        Response response = given().spec(Api.request("gateway")).accept("text/plain").when().get(path);
        //Then
        response.then().statusCode(200).body(containsString("jvm_memory_used_bytes"));
    }

    @Test
    void shouldReportDependenciesWhenGatewayIsReady() {
        //Given
        String path = "/health";
        //When
        Response response = given().spec(Api.request("gateway")).when().get(path);
        //Then
        response.then().statusCode(200).body("status", equalTo("ok"))
                .body("service", equalTo("gateway")).body("services.switch", equalTo("ok"))
                .body("services.authorization", equalTo("ok")).body("services.cardManagement", equalTo("ok"))
                .body("services.logger", equalTo("ok"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/v3/api-docs", "/switch-docs", "/logger-docs", "/terminal-docs", "/merchant-docs", "/cards-docs"})
    void shouldExposeOpenApiWhenDocumentationRouteIsRequested(String path) {
        //Given
        String route = path;
        //When
        Response response = given().spec(Api.request("gateway")).when().get(route);
        //Then
        response.then().statusCode(200).body("openapi", startsWith("3."))
                .body("info.title", not(isEmptyOrNullString())).body("paths", notNullValue());
    }

    @Test
    void shouldCacheAndInvalidateWhenCardIsReadThenChanged() {
        //Given
        String pan = Data.createCard().get("pan").toString();
        given().spec(Api.request("gateway")).when().get("/api/cards/" + pan)
                .then().statusCode(200).header("X-Cache", equalTo("MISS")).body("status", equalTo("ACTIVE"));
        //When
        Response hit = given().spec(Api.request("gateway")).when().get("/api/cards/" + pan);
        //Then
        hit.then().statusCode(200).header("X-Cache", equalTo("HIT")).body("status", equalTo("ACTIVE"));
        Data.patch(pan, Map.of("status", "BLOCKED"));
        given().spec(Api.request("gateway")).when().get("/api/cards/" + pan)
                .then().statusCode(200).header("X-Cache", equalTo("MISS")).body("status", equalTo("BLOCKED"));
    }

    @Test
    void shouldReturnConsistentStatisticsWhenOwnTransactionIsStored() {
        //Given
        Data.store(Data.transaction(Data.createCard().get("pan").toString()));
        //When
        Response response = given().spec(Api.request("gateway")).when().get("/api/dashboard/stats");
        //Then
        response.then().statusCode(200).body("totalTransactions", greaterThanOrEqualTo(1))
                .body("approvedCount", greaterThanOrEqualTo(1)).body("declinedCount", greaterThanOrEqualTo(0))
                .body("approvalRate", allOf(greaterThanOrEqualTo(0f), lessThanOrEqualTo(1f)))
                .body("totalAmount", greaterThanOrEqualTo(1000)).body("averageAmount", greaterThanOrEqualTo(0))
                .body("avgProcessingTimeMs", greaterThanOrEqualTo(0f));
        assertThat(response.jsonPath().getLong("approvedCount") + response.jsonPath().getLong("declinedCount"),
                equalTo(response.jsonPath().getLong("totalTransactions")));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 500})
    void shouldApplyRecentLimitWhenListingTransactions(int limit) {
        //Given
        Data.store(Data.transaction(Data.createCard().get("pan").toString()));
        //When
        Response response = given().spec(Api.request("gateway")).queryParam("limit", limit)
                .when().get("/api/dashboard/recent");
        //Then
        response.then().statusCode(200)
                .body("size()", allOf(greaterThanOrEqualTo(1), lessThanOrEqualTo(limit)))
                .body("id", everyItem(notNullValue()));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 501, -1})
    void shouldRejectRecentWhenLimitIsInvalid(int limit) {
        //Given
        int pageSize = limit;
        //When
        Response response = given().spec(Api.request("gateway")).queryParam("limit", pageSize)
                .when().get("/api/dashboard/recent");
        //Then
        response.then().statusCode(400).body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString("limit"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"hour", "day"})
    void shouldReturnTimeBucketsWhenGranularityIsValid(String granularity) {
        //Given
        Map<String, Object> tx = Data.transaction(Data.createCard().get("pan").toString());
        Data.store(tx);
        //When
        Response response = given().spec(Api.request("gateway")).queryParam("granularity", granularity)
                .queryParam("from", "2020-01-01T00:00:00Z").queryParam("to", "2040-01-01T00:00:00Z")
                .when().get("/api/dashboard/charts");
        //Then
        response.then().statusCode(200).body("size()", greaterThanOrEqualTo(1))
                .body("timestamp", everyItem(notNullValue())).body("total", everyItem(greaterThanOrEqualTo(1)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"minute", "", "DAY"})
    void shouldRejectChartsWhenGranularityIsInvalid(String granularity) {
        //Given
        String parameter = granularity;
        //When
        Response response = given().spec(Api.request("gateway")).queryParam("granularity", parameter)
                .when().get("/api/dashboard/charts");
        //Then
        response.then().statusCode(400).body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString("granularity"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"normal", "mixed", "high_value", "night_time", "declines_test"})
    void shouldRunTerminalWhenScenarioIsSupported(String scenario) {
        //Given
        Data.createCard(100000000, 10000000, 100000000);
        String blocked = Data.createCard().get("pan").toString();
        Data.patch(blocked, Map.of("status", "BLOCKED"));
        //When
        Response response = given().spec(Api.request("gateway"))
                .body(Map.of("count", 1, "scenario", scenario, "tps", 10))
                .when().post("/api/simulator/terminal/run");
        //Then
        response.then().statusCode(200).body("totalSubmitted", equalTo(1)).body("transactions", hasSize(1))
                .body("elapsedMs", greaterThanOrEqualTo(0));
        assertThat(response.jsonPath().getInt("approved") + response.jsonPath().getInt("declined"), equalTo(1));
    }

    @ParameterizedTest
    @CsvSource({"0,normal,1", "1,normal,0", "1,unknown,1"})
    void shouldRejectTerminalWhenRequestIsInvalid(int count, String scenario, int tps) {
        //Given
        Map<String, Object> body = Map.of("count", count, "scenario", scenario, "tps", tps);
        //When
        Response response = given().spec(Api.request("gateway")).body(body)
                .when().post("/api/simulator/terminal/run");
        //Then
        response.then().statusCode(400).body("message", not(isEmptyOrNullString()));
    }

    @Test
    void shouldStartAndStopContinuousTerminalWhenRequestIsValid() {
        //Given
        Data.createCard();
        Map<String, Object> body = Map.of("tps", 1, "transactionType", "NORMAL");
        //When
        Response response = given().spec(Api.request("gateway")).body(body)
                .when().post("/api/simulator/terminal/start-continuous");
        //Then
        try {
            response.then().statusCode(200).body(isEmptyString());
            given().spec(Api.request("gateway")).body(body)
                    .when().post("/api/simulator/terminal/start-continuous").then().statusCode(422)
                    .body("serviceName", equalTo("terminal-simulator")).body("message", containsString("already running"));
        } finally {
            given().spec(Api.request("gateway")).when().post("/api/simulator/terminal/stop")
                    .then().statusCode(200).body(isEmptyString());
        }
    }

    @Test
    void shouldRejectContinuousStartWhenTpsIsZero() {
        //Given
        Map<String, Object> body = Map.of("tps", 0, "transactionType", "NORMAL");
        //When
        Response response = given().spec(Api.request("gateway")).body(body)
                .when().post("/api/simulator/terminal/start-continuous");
        //Then
        response.then().statusCode(400).body("serviceName", equalTo("terminal-simulator"))
                .body("message", containsString("tps"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"grocery", "electronics", "restaurant", "travel"})
    void shouldRunMerchantWhenScenarioIsSupported(String scenario) {
        //Given
        Data.createCard(100000000, 10000000, 100000000);
        //When
        Response response = given().spec(Api.request("gateway"))
                .body(Map.of("count", 1, "scenario", scenario, "tps", 10))
                .when().post("/api/simulator/merchant/run");
        //Then
        response.then().statusCode(200).body("totalSubmitted", equalTo(1)).body("transactions", hasSize(1));
    }

    @Test
    void shouldReturnNotFoundWhenFeeHasNoMatchingTransaction() {
        //Given
        Map<String, Object> body = Data.authorization(Data.createCard().get("pan").toString(), 1000);
        //When
        Response response = given().spec(Api.request("gateway")).body(body)
                .when().post("/api/simulator/merchant/fee");
        //Then
        response.then().statusCode(404).body("message", not(isEmptyOrNullString()));
    }

    @Test
    void shouldReturnStoredFeeWhenMerchantTransactionCanBeFound() {
        //Given
        Data.createCard(100000000, 10000000, 100000000);
        String startedAt = java.time.Instant.now().minusMillis(1).toString();
        String stan = given().spec(Api.request("gateway"))
                .body(Map.of("count", 1, "scenario", "grocery", "tps", 1))
                .when().post("/api/simulator/merchant/run").then().statusCode(200)
                .body("transactions", hasSize(1)).extract().path("transactions[0].stan");
        String ownRows = "transactions.findAll { it.stan == '" + stan + "' && it.createdAt > '"
                + startedAt + "' && it.acquiringFee != null }";
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                given().spec(Api.request("gateway")).queryParam("limit", 500)
                        .when().get("/api/transactions/search").then().statusCode(200)
                        .body(ownRows, hasSize(1)));
        Map<String, Object> tx = given().spec(Api.request("gateway")).queryParam("limit", 500)
                .when().get("/api/transactions/search").then().statusCode(200)
                .extract().jsonPath().getMap(ownRows + "[0]");
        //When
        Response response = given().spec(Api.request("gateway")).body(tx)
                .when().post("/api/simulator/merchant/fee");
        //Then
        response.then().statusCode(200).body("acquirerFee", notNullValue());
        assertThat(response.jsonPath().getDouble("acquirerFee"), greaterThanOrEqualTo(0d));
    }

    @ParameterizedTest
    @ValueSource(strings = {"400000", "400001"})
    void shouldLookupIssuerWhenAuthorizationUsesKnownBin(String bin) {
        //Given
        String path = "/api/bin/" + bin;
        //When
        Response response = given().spec(Api.request("bin")).when().get(path);
        //Then
        response.then().statusCode(200).body("issuerId", startsWith("ISS")).body("issuerName", notNullValue());
    }
}
