package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.List;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** Проверяет ограничения DTO и контроллеров независимо от валидации Gateway. */
class ContractEdgesApiTest {
    @ParameterizedTest
    @CsvSource({"DELETE,", "PATCH,", "POST,reserve", "POST,rollback"})
    void shouldRejectCardCommandWhenPathPanIsMalformed(String method, String operation) {
        //Given
        String path = "/api/cards/123" + (operation == null ? "" : "/" + operation);
        Map<String, Object> body = Map.of("pan", "4000000000000002", "amount", 1000,
                "rrn", "000000" + Data.stan(), "status", "BLOCKED");
        //When
        Response response = given().spec(Api.request("gateway")).body(body).when().request(method, path);
        //Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"))
                .body("message", allOf(containsString("pan"), containsString("16")));
    }

    @Test
    void shouldRejectGenerationWhenBinHasNoIssuerMapping() {
        //Given
        Map<String, Object> body = Map.of("count", 1, "bins", List.of("999999"));
        //When
        Response response = given().spec(Api.request("gateway")).body(body).when().post("/api/cards/generate");
        //Then
        response.then().statusCode(404).body("error", equalTo("BinNotFoundException"))
                .body("serviceName", equalTo("card-management"));
    }

    @Test
    void shouldReturnNotFoundWhenPatchingUnknownCard() {
        //Given
        String pan = "999999" + String.format("%010d", Math.floorMod(System.nanoTime(), 10000000000L));
        //When
        Response response = given().spec(Api.request("gateway")).body(Map.of("status", "BLOCKED"))
                .when().patch("/api/cards/" + pan);
        //Then
        response.then().statusCode(404).body("serviceName", equalTo("card-management"))
                .body("error", equalTo("CardNotFoundException"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"rrn", "pan", "amount"})
    void shouldIdentifyMissingFieldWhenRollbackRequestIsInvalid(String field) {
        //Given
        Map<String, Object> body = new java.util.LinkedHashMap<>(
                Map.of("pan", "4000000000000002", "rrn", "000000" + Data.stan(), "amount", 1000));
        body.remove(field);
        //When
        Response response = given().spec(Api.request("authorization")).body(body)
                .when().post("/api/internal/rollback");
        //Then
        response.then().statusCode(400).body("serviceName", equalTo("authorization"))
                .body("message", containsString(field));
    }

    @ParameterizedTest
    @CsvSource({"gateway,/api/cards", "switch,/api/internal/route", "authorization,/api/internal/rollback"})
    void shouldRejectMalformedBodyWhenHttpContractRequiresJson(String service, String path) {
        //Given
        String body = "{";
        //When
        Response response = given().spec(Api.request(service)).body(body).when().post(path);
        //Then
        response.then().statusCode(400).body("error", not(isEmptyOrNullString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"transmissionDateTime", "stan", "pan", "terminalId"})
    void shouldRejectFeeLookupWhenRequiredFieldIsMissing(String field) {
        //Given
        Map<String, Object> body = Data.authorization("4000000000000002", 1000);
        body.remove(field);
        //When
        Response response = given().spec(Api.request("gateway")).body(body)
                .when().post("/api/simulator/merchant/fee");
        //Then
        response.then().statusCode(400).body("message", containsString(field));
    }

    @Test
    void shouldRejectMerchantWhenNoMerchantMatchesRequestedMcc() {
        //Given
        Data.createCard();
        //When
        Response response = given().spec(Api.request("gateway"))
                .body(Map.of("count", 1, "scenario", "grocery", "mccCodes", List.of("0000")))
                .when().post("/api/simulator/merchant/run");
        //Then
        response.then().statusCode(404).body("message", not(isEmptyOrNullString()));
    }

    @Test
    void shouldRejectCreationWhenBinHasNoIssuerMapping() {
        //Given
        Map<String, Object> body = Data.cardBody(10000, 1000, 5000);
        body.put("bin", "999999");
        //When
        Response response = given().spec(Api.request("gateway")).body(body).when().post("/api/cards");
        //Then
        response.then().statusCode(404).body("error", equalTo("BinNotFoundException"))
                .body("serviceName", equalTo("card-management")).body("message", containsString("999999"));
    }

    @ParameterizedTest
    @MethodSource("internalInvalidFields")
    void shouldRejectInternalAuthorizationWhenDtoConstraintIsViolated(String field, Object value) {
        //Given
        Map<String, Object> body = Data.authorization("4000000000000002", 1000);
        body.put(field, value);
        //When
        Response response = given().spec(Api.request("authorization")).body(body)
                .when().post("/api/internal/authorize");
        //Then
        response.then().statusCode(400).body("serviceName", equalTo("authorization"))
                .body("error", equalTo("VALIDATION_ERROR")).body("message", containsString(field));
    }

    static Stream<Arguments> internalInvalidFields() {
        return Stream.of(Arguments.of("mti", "010"), Arguments.of("mti", "01000"),
                Arguments.of("stan", "12345"), Arguments.of("stan", "1234567"),
                Arguments.of("processingCode", "12345"), Arguments.of("processingCode", "1234567"),
                Arguments.of("terminalId", "TERM001"), Arguments.of("terminalId", "TERM00012"),
                Arguments.of("merchantId", "SHORT"), Arguments.of("merchantId", "1234567890123456"),
                Arguments.of("issuerId", null), Arguments.of("amount", -1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"reserve", "rollback"})
    void shouldRejectCommandWhenRrnIsMalformed(String operation) {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Map<String, Object> body = Map.of("pan", pan, "amount", 1000, "rrn", "12345");
        //When
        Response response = given().spec(Api.request("gateway")).body(body)
                .when().post("/api/cards/" + pan + "/" + operation);
        //Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"))
                .body("message", containsString("12"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"reserve", "rollback"})
    void shouldReturnNotFoundWhenCommandReferencesUnknownCard(String operation) {
        //Given
        String pan = "999999" + String.format("%010d", Math.floorMod(System.nanoTime(), 10000000000L));
        //When
        Response response = given().spec(Api.request("gateway"))
                .body(Map.of("pan", pan, "amount", 1000, "rrn", "000000" + Data.stan()))
                .when().post("/api/cards/" + pan + "/" + operation);
        //Then
        response.then().statusCode(404).body("error", equalTo("CardNotFoundException"))
                .body("serviceName", equalTo("card-management"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INACTIVE", "BLOCKED", "EXPIRED"})
    void shouldRejectReserveWhenCardCannotBeUsed(String status) {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Data.patch(pan, Map.of("status", status));
        //When
        Response response = given().spec(Api.request("gateway"))
                .body(Map.of("amount", 1000, "rrn", "000000" + Data.stan()))
                .when().post("/api/cards/" + pan + "/reserve");
        //Then
        response.then().statusCode(409).body("serviceName", equalTo("card-management"))
                .body("message", containsString(status));
    }

    @ParameterizedTest
    @MethodSource("invalidPatch")
    void shouldRejectPatchWhenLimitsViolateDomainRules(Map<String, Object> patch) {
        //Given
        String pan = Data.createCard(10000, 2000, 5000).get("pan").toString();
        //When
        Response response = given().spec(Api.request("gateway")).body(patch).when().patch("/api/cards/" + pan);
        //Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"))
                .body("message", not(isEmptyOrNullString()));
    }

    static Stream<Arguments> invalidPatch() {
        return Stream.of(Arguments.of(Map.of("dailyLimit", -1)), Arguments.of(Map.of("monthlyLimit", -1)),
                Arguments.of(Map.of("dailyLimit", 5001)), Arguments.of(Map.of("monthlyLimit", 1999)));
    }

    @Test
    void shouldRejectBulkUpdateWhenBothSelectorsAreProvided() {
        //Given
        String pan = Data.createCard().get("pan").toString();
        //When
        Response response = given().spec(Api.request("gateway"))
                .body(Map.of("bins", List.of("400000"), "pans", List.of(pan), "status", "BLOCKED"))
                .when().post("/api/cards/bulk-update");
        //Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"))
                .body("message", not(isEmptyOrNullString()));
    }

    @ParameterizedTest
    @MethodSource("invalidMerchant")
    void shouldRejectMerchantSimulationWhenOneInputIsInvalid(Map<String, Object> body) {
        //Given
        Map<String, Object> request = body;
        //When
        Response response = given().spec(Api.request("gateway")).body(request)
                .when().post("/api/simulator/merchant/run");
        //Then
        response.then().statusCode(400).body("message", not(isEmptyOrNullString()));
    }

    static Stream<Arguments> invalidMerchant() {
        return Stream.of(Arguments.of(Map.of("count", 0, "scenario", "grocery")),
                Arguments.of(Map.of("count", 1, "scenario", "unknown")),
                Arguments.of(Map.of("count", 1, "scenario", "grocery", "tps", 0)),
                Arguments.of(Map.of("count", 1)));
    }

    @Test
    void shouldExposeRouteMismatchWhenMerchantListIsRequestedThroughGateway() {
        //Given
        String path = "/api/simulator/merchants";
        //When
        Response response = given().spec(Api.request("gateway")).when().get(path);
        //Then
        // D03: контроллер существует, но Gateway маршрутизирует только /api/simulator/merchant/**.
        response.then().statusCode(404).body("status", equalTo(404));
        given().spec(Api.request("merchant")).when().get(path).then().statusCode(200)
                .body("size()", greaterThanOrEqualTo(1)).body("mcc", everyItem(notNullValue()));
    }

    @Test
    void shouldReturnUnknownBinWhenLookupHasNoMapping() {
        //Given
        String path = "/api/bin/999999";
        //When
        Response response = given().spec(Api.request("bin")).when().get(path);
        //Then
        response.then().statusCode(404).body(isEmptyString());
    }

    @Test
    void shouldExposeSimulatedFailureWhenLookupFailFlagIsSet() {
        //Given
        String path = "/api/bin/400000";
        //When
        Response response = given().spec(Api.request("bin")).queryParam("fail", true).when().get(path);
        //Then
        response.then().statusCode(500).body(isEmptyString());
    }
}
