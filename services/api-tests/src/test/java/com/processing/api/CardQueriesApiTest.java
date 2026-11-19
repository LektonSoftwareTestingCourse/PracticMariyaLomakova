package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class CardQueriesApiTest {
    @ParameterizedTest
    @MethodSource("validQueries")
    void shouldIncludeOwnCardWhenOptionalFilterMatches(String parameter, String value) {
        //Given
        String pan = Data.createCard().get("pan").toString();
        //When
        Response response = given().spec(Api.request("gateway")).queryParam(parameter, value)
                .queryParam("limit", 10000).when().get("/api/cards");
        //Then
        response.then().statusCode(200).body("cards.findAll { it.pan == '" + pan + "' }", hasSize(1));
    }

    static Stream<Arguments> validQueries() {
        return Stream.of(Arguments.of("status", "ACTIVE"), Arguments.of("bin", "400000"),
                Arguments.of("issuerId", "ISS001"), Arguments.of("startDate", "2020-01-01T00:00:00Z"),
                Arguments.of("endDate", "2040-01-01T00:00:00Z"));
    }

    @ParameterizedTest
    @MethodSource("invalidQueries")
    void shouldIdentifyErrorWhenCardQueryHasInvalidValue(String parameter, String value, String rule) {
        //Given
        Map<String, String> query = Map.of(parameter, value);
        //When
        Response response = given().spec(Api.request("gateway")).queryParams(query).when().get("/api/cards");
        //Then
        response.then().statusCode(400).body("serviceName", equalTo("card-management"))
                .body("message", allOf(containsString(parameter), containsString(rule)));
    }

    static Stream<Arguments> invalidQueries() {
        return Stream.of(Arguments.of("bin", "12345", "exactly 6 digits"),
                Arguments.of("bin", "1234567", "exactly 6 digits"), Arguments.of("bin", "12345A", "exactly 6 digits"),
                Arguments.of("issuerId", "lowercase", "must match"), Arguments.of("issuerId", "TOOLONGISSUER", "1 and 10"),
                Arguments.of("status", "UNKNOWN", "invalid parameter value"),
                Arguments.of("startDate", "not-a-date", "invalid parameter value"),
                Arguments.of("endDate", "2026-02-30", "invalid parameter value"),
                Arguments.of("limit", "abc", "invalid parameter value"),
                Arguments.of("offset", "abc", "invalid parameter value"));
    }

    @ParameterizedTest
    @MethodSource("filterPairs")
    void shouldApplyBothCardFiltersWhenParametersAreCombined(String first, String second) {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Map<String, Object> values = Map.of("status", "ACTIVE", "bin", "400000", "issuerId", "ISS001",
                "startDate", "2020-01-01T00:00:00Z", "endDate", "2040-01-01T00:00:00Z");
        Map<String, Object> mismatches = Map.of("status", "BLOCKED", "bin", "555555", "issuerId", "ISS002",
                "startDate", "2100-01-01T00:00:00Z", "endDate", "2000-01-01T00:00:00Z");
        Map<String, Object> query = new LinkedHashMap<>(Map.of("limit", 10000));
        query.put(first, values.get(first));
        query.put(second, values.get(second));
        //When
        Response response = given().spec(Api.request("gateway")).queryParams(query).when().get("/api/cards");
        //Then
        response.then().statusCode(200).body("cards.findAll { it.pan == '" + pan + "' }", hasSize(1));
        Map<String, Object> firstNoMatch = new LinkedHashMap<>(query);
        firstNoMatch.put(first, mismatches.get(first));
        given().spec(Api.request("gateway")).queryParams(firstNoMatch).when().get("/api/cards")
                .then().statusCode(200).body("cards.findAll { it.pan == '" + pan + "' }", hasSize(0));
        Map<String, Object> secondNoMatch = new LinkedHashMap<>(query);
        secondNoMatch.put(second, mismatches.get(second));
        given().spec(Api.request("gateway")).queryParams(secondNoMatch).when().get("/api/cards")
                .then().statusCode(200).body("cards.findAll { it.pan == '" + pan + "' }", hasSize(0));
    }

    static Stream<Arguments> filterPairs() {
        String[] names = {"status", "bin", "issuerId", "startDate", "endDate"};
        Stream.Builder<Arguments> pairs = Stream.builder();
        for (int first = 0; first < names.length; first++) {
            for (int second = first + 1; second < names.length; second++) {
                pairs.add(Arguments.of(names[first], names[second]));
            }
        }
        return pairs.build();
    }

    @Test
    void shouldReturnEmptyWhenCreationDateIsOutsideRange() {
        //Given
        Data.createCard();
        //When
        Response response = given().spec(Api.request("gateway")).queryParam("startDate", "2100-01-01T00:00:00Z")
                .when().get("/api/cards");
        //Then
        response.then().statusCode(200).body("total", equalTo(0)).body("cards", hasSize(0));
    }

    @ParameterizedTest
    @CsvSource({"1,0", "10000,0", "1,1000000"})
    void shouldAcceptBoundaryWhenCardPaginationIsWithinConfiguredRange(int limit, int offset) {
        //Given
        Data.createCard();
        //When
        Response response = given().spec(Api.request("gateway")).queryParam("limit", limit)
                .queryParam("offset", offset).when().get("/api/cards");
        //Then
        response.then().statusCode(200).body("total", greaterThanOrEqualTo(1))
                .body("cards.size()", lessThanOrEqualTo(limit));
    }
}
