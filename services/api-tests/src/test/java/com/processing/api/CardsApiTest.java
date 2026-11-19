package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.Arguments;

import java.util.Map;
import java.util.List;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class CardsApiTest {
    @Test
    void shouldCreateAndReadCardWhenFieldsAreValid() {
        //Given
        Map<String, Object> body = Data.cardBody(10000, 2000, 5000);
        //When
        Response created = given().spec(Api.request("gateway")).body(body).when().post("/api/cards");
        //Then
        created.then().statusCode(201).body("id", matchesPattern("[0-9a-f-]{36}"))
                .body("pan", matchesPattern("400000\\d{10}"))
                .body("availableBalance", equalTo(10000)).body("dailyLimit", equalTo(2000))
                .body("monthlyLimit", equalTo(5000)).body("cardholderName", equalTo("API TEST"))
                .body("currencyCode", equalTo("643")).body("issuerId", equalTo("ISS001"))
                .body("createdAt", notNullValue()).body("status", equalTo("ACTIVE"));
        given().spec(Api.request("gateway")).when().get("/api/cards/" + created.path("pan"))
                .then().statusCode(200).body("id", equalTo(created.path("id")))
                .body("availableBalance", equalTo(10000));
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidCreation")
    void shouldRejectCreationWhenOneFieldIsInvalid(String field, Object value, String rule) {
        //Given
        Map<String, Object> body = Data.cardBody(10000, 2000, 5000);
        body.put(field, value);
        //When
        Response response = given().spec(Api.request("gateway")).body(body).when().post("/api/cards");
        //Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"))
                .body("error", not(isEmptyOrNullString()))
                .body("message", allOf(containsString(field), matchesPattern("(?s).*" + rule + ".*")))
                .body("timestamp", notNullValue());
    }

    static Stream<Arguments> invalidCreation() {
        return Stream.of(Arguments.of("bin", null, "(blank|6 digits)"), Arguments.of("bin", "", "(blank|6 digits)"),
                Arguments.of("bin", "40000", "6 digits"), Arguments.of("bin", "4000000", "6 digits"),
                Arguments.of("bin", "40000A", "6 digits"), Arguments.of("cardholderName", null, "blank"),
                Arguments.of("cardholderName", "", "(blank|uppercase)"),
                Arguments.of("cardholderName", "John", "uppercase"), Arguments.of("currencyCode", null, "blank"),
                Arguments.of("currencyCode", "64", "exactly 3"), Arguments.of("currencyCode", "6430", "exactly 3"),
                Arguments.of("currencyCode", "RUB", "digits only"), Arguments.of("dailyLimit", -1, "negative"),
                Arguments.of("monthlyLimit", -1, "negative"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"4", "400000000000000", "40000000000000000", "400000000000000A"})
    void shouldRejectReadWhenPanFormatIsInvalid(String pan) {
        //Given
        String path = "/api/cards/" + pan;
        //When
        Response response = given().spec(Api.request("gateway")).when().get(path);
        //Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"))
                .body("message", not(isEmptyOrNullString()));
    }

    @Test
    void shouldReturnNotFoundWhenCardDoesNotExist() {
        //Given
        String pan = "999999" + String.format("%010d", Math.floorMod(System.nanoTime(), 10000000000L));
        //When
        Response response = given().spec(Api.request("gateway")).when().get("/api/cards/" + pan);
        //Then
        response.then().statusCode(404).body("serviceName", equalTo("card-management"))
                .body("error", equalTo("CardNotFoundException")).body("message", not(isEmptyOrNullString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "INACTIVE", "BLOCKED", "EXPIRED"})
    void shouldPatchOnlySuppliedFieldsWhenCardExists(String status) {
        //Given
        Map<String, Object> card = Data.createCard(10000, 2000, 5000);
        //When
        Response response = given().spec(Api.request("gateway")).body(Map.of("status", status, "dailyLimit", 1000))
                .when().patch("/api/cards/" + card.get("pan"));
        //Then
        response.then().statusCode(200).body("id", equalTo(card.get("id"))).body("status", equalTo(status))
                .body("dailyLimit", equalTo(1000)).body("monthlyLimit", equalTo(5000))
                .body("availableBalance", equalTo(10000));
        given().spec(Api.request("gateway")).when().get("/api/cards/" + card.get("pan"))
                .then().statusCode(200).body("status", equalTo(status)).body("dailyLimit", equalTo(1000));
    }

    @Test
    void shouldDeleteAndRejectRepeatWhenCardExists() {
        //Given
        String pan = Data.createCard().get("pan").toString();
        given().spec(Api.request("gateway")).when().get("/api/cards/" + pan)
                .then().statusCode(200).body("status", equalTo("ACTIVE"));
        //When
        Response response = given().spec(Api.request("gateway")).when().delete("/api/cards/" + pan);
        //Then
        response.then().statusCode(204).body(isEmptyString());
        given().spec(Api.request("gateway")).when().get("/api/cards/" + pan)
                .then().statusCode(404).body("serviceName", equalTo("card-management"));
        given().spec(Api.request("cards")).when().delete("/api/cards/" + pan).then().statusCode(404)
                .body("serviceName", equalTo("card-management")).body("message", not(isEmptyOrNullString()));
    }

    @ParameterizedTest
    @CsvSource({"1, 9999", "9999, 1", "10000, 0"})
    void shouldReserveAndRollbackWhenBalanceIsSufficient(int amount, int remaining) {
        //Given
        String pan = Data.createCard(10000, 20000, 50000).get("pan").toString();
        String rrn = "000000" + Data.stan();
        //When
        Response response = given().spec(Api.request("gateway")).body(Map.of("amount", amount, "rrn", rrn))
                .when().post("/api/cards/" + pan + "/reserve");
        //Then
        response.then().statusCode(200).body("pan", equalTo(pan)).body("availableBalance", equalTo(remaining));
        given().spec(Api.request("gateway")).body(Map.of("pan", pan, "amount", amount, "rrn", rrn))
                .when().post("/api/cards/" + pan + "/rollback").then().statusCode(200)
                .body("pan", equalTo(pan)).body("availableBalance", equalTo(10000));
        given().spec(Api.request("gateway")).body(Map.of("pan", pan, "amount", amount, "rrn", rrn))
                .when().post("/api/cards/" + pan + "/rollback").then().statusCode(409)
                .body("serviceName", equalTo("card-management")).body("message", not(isEmptyOrNullString()));
    }

    @Test
    void shouldRejectReserveWhenAmountExceedsBalance() {
        //Given
        String pan = Data.createCard(10000, 20000, 50000).get("pan").toString();
        //When
        Response response = given().spec(Api.request("gateway"))
                .body(Map.of("amount", 10001, "rrn", "000000" + Data.stan()))
                .when().post("/api/cards/" + pan + "/reserve");
        //Then
        response.then().statusCode(402).body("error", equalTo("InsufficientFundsException"))
                .body("serviceName", equalTo("card-management")).body("message", not(isEmptyOrNullString()));
        assertThat(Data.balance(pan).intValueExact(), equalTo(10000));
    }

    @Test
    void shouldRejectDuplicateReservationWhenRrnIsRepeated() {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Map<String, Object> body = Map.of("amount", 1000, "rrn", "000000" + Data.stan());
        given().spec(Api.request("gateway")).body(body).when().post("/api/cards/" + pan + "/reserve")
                .then().statusCode(200).body("availableBalance", equalTo(999000));
        //When
        Response response = given().spec(Api.request("gateway")).body(body)
                .when().post("/api/cards/" + pan + "/reserve");
        //Then
        response.then().statusCode(409).body("error", equalTo("ReservationAlreadyExistsException"));
        assertThat(Data.balance(pan).intValueExact(), equalTo(999000));
    }

    @Test
    void shouldBulkUpdateOnlySelectedPansWhenSelectorIsValid() {
        //Given
        String pan = Data.createCard().get("pan").toString();
        String other = Data.createCard().get("pan").toString();
        //When
        Response response = given().spec(Api.request("gateway"))
                .body(Map.of("pans", List.of(pan), "status", "BLOCKED")).when().post("/api/cards/bulk-update");
        //Then
        response.then().statusCode(200).body("updated", equalTo(1));
        given().spec(Api.request("cards")).when().get("/api/cards/" + pan)
                .then().statusCode(200).body("status", equalTo("BLOCKED"));
        given().spec(Api.request("cards")).when().get("/api/cards/" + other)
                .then().statusCode(200).body("status", equalTo("ACTIVE"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void shouldGenerateRequestedNumberWhenCountIsValid(int count) {
        //Given
        Map<String, Object> body = Map.of("count", count, "bins", List.of("400000"));
        //When
        Response response = given().spec(Api.request("gateway")).body(body).when().post("/api/cards/generate");
        //Then
        response.then().statusCode(201).body("generated", equalTo(count)).body("cards", hasSize(count))
                .body("cards.pan", everyItem(matchesPattern("400000\\d{10}")));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 10001})
    void shouldRejectGenerationWhenCountIsOutOfRange(int count) {
        //Given
        Map<String, Object> body = Map.of("count", count, "bins", List.of("400000"));
        //When
        Response response = given().spec(Api.request("gateway")).body(body).when().post("/api/cards/generate");
        //Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"))
                .body("message", not(isEmptyOrNullString()));
    }

    @ParameterizedTest
    @CsvSource({"0,0,400,limit,greater than 0", "1,-1,400,offset,negative", "10001,0,413,limit,10000"})
    void shouldRejectListWhenPaginationIsInvalid(int limit, int offset, int expected, String field, String rule) {
        //Given
        Map<String, Integer> query = Map.of("limit", limit, "offset", offset);
        //When
        Response response = given().spec(Api.request("gateway")).queryParams(query).when().get("/api/cards");
        //Then
        response.then().statusCode(expected).body("serviceName", equalTo("card-management"))
                .body("message", allOf(containsString(field), containsString(rule)));
    }

    @Test
    void shouldFilterAndPageCardsWhenStatusAndBinAreSpecified() {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Data.patch(pan, Map.of("status", "BLOCKED"));
        //When
        Response response = given().spec(Api.request("gateway")).queryParam("bin", "400000")
                .queryParam("status", "BLOCKED").queryParam("issuerId", "ISS001")
                .queryParam("limit", 1).queryParam("offset", 0).when().get("/api/cards");
        //Then
        response.then().statusCode(200).body("cards", hasSize(1)).body("total", greaterThanOrEqualTo(1))
                .body("cards.bin", everyItem(equalTo("400000")))
                .body("cards.status", everyItem(equalTo("BLOCKED")));
    }
}
