package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class SearchApiTest {
    @Test
    void shouldRejectExportWhenFilterPaginationIsInvalid() {
        //Given
        int limit = 0;
        //When
        Response response = given().spec(Api.request("gateway")).queryParam("limit", limit)
                .when().get("/api/transactions/export");
        //Then
        response.then().statusCode(400).body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString("limit"));
    }

    @Test
    void shouldRejectChartsWhenRangeIsReversed() {
        //Given
        Map<String, String> query = Map.of("from", "2026-06-02T00:00:00Z", "to", "2026-06-01T00:00:00Z");
        //When
        Response response = given().spec(Api.request("gateway")).queryParams(query)
                .when().get("/api/dashboard/charts");
        //Then
        response.then().statusCode(400).body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString("must not be after"));
    }

    @ParameterizedTest
    @CsvSource({"1,0,1", "2,0,2", "500,0,2", "1,1,1", "1,2,0"})
    void shouldReturnRequestedPageWhenLimitAndOffsetAreValid(int limit, int offset, int size) {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Data.store(Data.transaction(pan));
        Data.store(Data.transaction(pan));
        //When
        Response response = given().spec(Api.request("gateway")).queryParam("pan", pan)
                .queryParam("limit", limit).queryParam("offset", offset).when().get("/api/transactions/search");
        //Then
        response.then().statusCode(200).body("total", equalTo(2)).body("transactions", hasSize(size))
                .body("transactions.pan", everyItem(equalTo(pan)));
    }

    @ParameterizedTest
    @MethodSource("invalidQueries")
    void shouldRejectSearchWhenOneQueryParameterIsInvalid(String parameter, String value, String rule) {
        //Given
        Map<String, String> query = Map.of(parameter, value);
        //When
        Response response = given().spec(Api.request("gateway")).queryParams(query)
                .when().get("/api/transactions/search");
        //Then
        response.then().statusCode(400).body("serviceName", equalTo("transaction-logger"))
                .body("message", allOf(containsString(parameter), containsString(rule)));
    }

    static Stream<Arguments> invalidQueries() {
        return Stream.of(Arguments.of("limit", "0", "greater than 0"),
                Arguments.of("limit", "501", "must not exceed 500"),
                Arguments.of("limit", "-1", "greater than 0"), Arguments.of("offset", "-1", "greater than or equal to 0"),
                Arguments.of("limit", "abc", "Failed to convert"), Arguments.of("status", "UNKNOWN", "Failed to convert"),
                Arguments.of("dateFrom", "2026-02-30", "Failed to convert"),
                Arguments.of("dateTo", "not-a-date", "Failed to convert"));
    }

    @Test
    void shouldRejectDateRangeWhenFromIsAfterTo() {
        //Given
        Map<String, String> query = Map.of("dateFrom", "2026-06-02", "dateTo", "2026-06-01");
        //When
        Response response = given().spec(Api.request("gateway")).queryParams(query)
                .when().get("/api/transactions/search");
        //Then
        response.then().statusCode(400).body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString("dateFrom must not be after dateTo"));
    }

    @Test
    void shouldReturnEmptyPageWhenNoTransactionMatchesOwnPan() {
        //Given
        String pan = Data.createCard().get("pan").toString();
        //When
        Response response = Data.search(pan);
        //Then
        response.then().statusCode(200).body("total", equalTo(0)).body("transactions", hasSize(0));
    }

    @ParameterizedTest
    @MethodSource("filterPairs")
    void shouldApplyBothFiltersWhenTwoParametersAreCombined(String first, String second) {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Map<String, Object> tx = Data.transaction(pan);
        tx.put("status", "DECLINED");
        tx.put("declineReason", "INSUFFICIENT_FUNDS");
        Data.store(tx);
        Map<String, Object> values = new LinkedHashMap<>(tx);
        LocalDate date = Instant.parse(tx.get("transmissionDateTime").toString()).atZone(ZoneOffset.UTC).toLocalDate();
        values.put("dateFrom", date.minusDays(1).toString());
        values.put("dateTo", date.plusDays(1).toString());
        Map<String, Object> query = new LinkedHashMap<>(Map.of("pan", pan, "limit", 500));
        query.put(first, values.get(first));
        query.put(second, values.get(second));
        Map<String, Object> mismatches = Map.of("pan", "999999" + pan.substring(6), "status", "APPROVED",
                "dateFrom", date.plusDays(1).toString(), "dateTo", date.minusDays(1).toString(),
                "merchantId", "NO_MATCH_" + Data.stan(), "issuerId", "OTHER", "mcc", "0000",
                "declineReason", "CARD_BLOCKED");
        //When
        Response response = given().spec(Api.request("gateway")).queryParams(query)
                .when().get("/api/transactions/search");
        //Then
        response.then().statusCode(200).body("transactions.findAll { it.id == '" + tx.get("id") + "' }", hasSize(1));
        Map<String, Object> noMatch = new LinkedHashMap<>(query);
        noMatch.put(first, mismatches.get(first));
        given().spec(Api.request("gateway")).queryParams(noMatch).when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(0)).body("transactions", hasSize(0));
        Map<String, Object> secondNoMatch = new LinkedHashMap<>(query);
        secondNoMatch.put(second, mismatches.get(second));
        given().spec(Api.request("gateway")).queryParams(secondNoMatch).when().get("/api/transactions/search")
                .then().statusCode(200).body("total", equalTo(0)).body("transactions", hasSize(0));
    }

    static Stream<Arguments> filterPairs() {
        String[] names = {"pan", "status", "dateFrom", "dateTo", "merchantId", "issuerId", "mcc", "declineReason"};
        Stream.Builder<Arguments> pairs = Stream.builder();
        for (int first = 0; first < names.length; first++) {
            for (int second = first + 1; second < names.length; second++) {
                pairs.add(Arguments.of(names[first], names[second]));
            }
        }
        return pairs.build();
    }

    @Test
    void shouldIncludeWholeUtcDayWhenDateRangeHasEqualBounds() {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Map<String, Object> start = Data.transaction(pan);
        start.put("transmissionDateTime", "2026-06-01T00:00:00Z");
        Map<String, Object> end = Data.transaction(pan);
        end.put("transmissionDateTime", "2026-06-02T00:00:00Z");
        Data.store(start);
        Data.store(end);
        //When
        Response response = given().spec(Api.request("gateway")).queryParam("pan", pan)
                .queryParam("dateFrom", "2026-06-01").queryParam("dateTo", "2026-06-01")
                .when().get("/api/transactions/search");
        //Then
        response.then().statusCode(200).body("total", equalTo(1)).body("transactions[0].id", equalTo(start.get("id")));
    }

    @Test
    void shouldExportOwnTransactionsAndHeadersWhenPanFilterIsSet() {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Map<String, Object> tx = Data.transaction(pan);
        Data.store(tx);
        //When
        Response response = given().spec(Api.request("gateway")).accept("text/csv").queryParam("pan", pan)
                .when().get("/api/transactions/export");
        //Then
        response.then().statusCode(200).contentType("text/csv")
                .header("Content-Disposition", equalTo("attachment; filename=\"transactions.csv\""))
                .body(containsString(pan)).body(containsString(tx.get("id").toString()));
    }
}
