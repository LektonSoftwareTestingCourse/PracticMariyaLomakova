package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class LoggerApiTest {
    @ParameterizedTest
    @CsvSource({
            "2026-06-01T12:00:00.123456123Z,2026-06-01T12:00:00.123456Z",
            "2026-06-01T12:00:00.123456499Z,2026-06-01T12:00:00.123456Z",
            "2026-06-01T12:00:00.123456500Z,2026-06-01T12:00:00.123457Z",
            "2026-06-01T12:00:00.123456789Z,2026-06-01T12:00:00.123457Z",
            "2026-06-01T12:00:00.999999500Z,2026-06-01T12:00:01Z"
    })
    void shouldKeepRepeatedDeliveryWhenTimestampHasNanoseconds(String timestamp, String storedTimestamp) {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Map<String, Object> tx = Data.transaction(pan);
        tx.put("transmissionDateTime", timestamp);
        Data.store(tx);
        //When
        Response response = given().spec(Api.request("logger")).body(tx).when().post("/api/internal/log");
        //Then
        response.then().statusCode(200).body("id", equalTo(tx.get("id")))
                .body("transmissionDateTime", equalTo(storedTimestamp));
        Data.search(pan).then().statusCode(200).body("total", equalTo(1))
                .body("transactions[0].id", equalTo(tx.get("id")))
                .body("transactions[0].transmissionDateTime", equalTo(storedTimestamp));
    }

    @Test
    void shouldRejectRepeatedDeliveryWhenBusinessTimestampChangesByOneMicrosecond() {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Map<String, Object> tx = Data.transaction(pan);
        tx.put("transmissionDateTime", "2026-06-01T12:00:00.123456789Z");
        Data.store(tx);
        tx.put("transmissionDateTime", "2026-06-01T12:00:00.123457789Z");
        //When
        Response response = given().spec(Api.request("logger")).body(tx).when().post("/api/internal/log");
        //Then
        response.then().statusCode(409).body("serviceName", equalTo("transaction-logger"))
                .body("error", equalTo("Transaction conflict")).body("message", containsString(tx.get("id").toString()));
        Data.search(pan).then().statusCode(200).body("total", equalTo(1))
                .body("transactions[0].id", equalTo(tx.get("id")))
                .body("transactions[0].transmissionDateTime", equalTo("2026-06-01T12:00:00.123457Z"));
    }

    @Test
    void shouldKeepOneOriginalRecordWhenDeliveryIsRepeated() {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Map<String, Object> tx = Data.transaction(pan);
        Data.store(tx);
        //When
        Response response = given().spec(Api.request("logger")).body(tx).when().post("/api/internal/log");
        //Then
        response.then().statusCode(200).body("id", equalTo(tx.get("id"))).body("amount", equalTo(1000))
                .body("pan", equalTo(pan)).body("status", equalTo("APPROVED"));
        Data.search(pan).then().statusCode(200).body("total", equalTo(1))
                .body("transactions.findAll { it.id == '" + tx.get("id") + "' }", hasSize(1));
    }

    @ParameterizedTest(name = "conflicting {0}")
    @MethodSource("conflictingFields")
    void shouldPreserveOriginalWhenSameIdHasDifferentBusinessData(String field, Object value) {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Map<String, Object> tx = Data.transaction(pan);
        Data.store(tx);
        tx.put(field, value);
        //When
        Response response = given().spec(Api.request("logger")).body(tx).when().post("/api/internal/log");
        //Then
        response.then().statusCode(409).body("serviceName", equalTo("transaction-logger"))
                .body("error", equalTo("Transaction conflict")).body("message", containsString(tx.get("id").toString()));
        Data.search(pan).then().statusCode(200).body("total", equalTo(1))
                .body("transactions[0].amount", equalTo(1000)).body("transactions[0].status", equalTo("APPROVED"));
    }

    static Stream<Arguments> conflictingFields() {
        return Stream.of(Arguments.of("amount", 1001), Arguments.of("status", "DECLINED"),
                Arguments.of("currencyCode", "840"), Arguments.of("merchantId", "OTHER0000000001"),
                Arguments.of("stan", "999999"));
    }

    @Test
    void shouldTreatTechnicalTimestampAndDecimalScaleAsEquivalentWhenRepeatingDelivery() {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Map<String, Object> tx = Data.transaction(pan);
        Data.store(tx);
        tx.put("createdAt", "2026-01-01T00:00:00Z");
        tx.put("amount", new java.math.BigDecimal("1000.00"));
        //When
        Response response = given().spec(Api.request("logger")).body(tx).when().post("/api/internal/log");
        //Then
        response.then().statusCode(200).body("id", equalTo(tx.get("id"))).body("amount", equalTo(1000));
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidFields")
    void shouldIdentifyInvalidFieldWhenLoggingMalformedTransaction(String field, Object value) {
        //Given
        Map<String, Object> tx = Data.transaction("4000000000000002");
        tx.put(field, value);
        //When
        Response response = given().spec(Api.request("logger")).body(tx).when().post("/api/internal/log");
        //Then
        response.then().statusCode(400).body("serviceName", equalTo("transaction-logger"))
                .body("message", containsString(field)).body("error", equalTo("Validation error"));
    }

    static Stream<Arguments> invalidFields() {
        Stream<Arguments> required = Stream.of("mti", "stan", "pan", "processingCode", "currencyCode",
                "terminalId", "merchantId", "mcc", "acquirerId").map(field -> Arguments.of(field, ""));
        Stream<Arguments> constraints = Stream.of(Arguments.of("id", null), Arguments.of("mti", "01000"),
                Arguments.of("stan", "12345"), Arguments.of("stan", "1234567"), Arguments.of("stan", "12345A"),
                Arguments.of("pan", "123"), Arguments.of("processingCode", "12345"),
                Arguments.of("processingCode", "12345A"), Arguments.of("amount", 0), Arguments.of("amount", -1),
                Arguments.of("amount", null), Arguments.of("currencyCode", "RU"), Arguments.of("currencyCode", "RUB"),
                Arguments.of("terminalId", "123456789"), Arguments.of("merchantId", "1234567890123456"),
                Arguments.of("mcc", "123"), Arguments.of("mcc", "123A"), Arguments.of("acquirerId", "12345678901"),
                Arguments.of("issuerId", "12345678901"), Arguments.of("acquiringFee", -1),
                Arguments.of("status", null), Arguments.of("authCode", "1234567"),
                Arguments.of("terminalType", "12345678901"), Arguments.of("declineReason", "X".repeat(101)),
                Arguments.of("rrn", "1234567890123"), Arguments.of("processingTimeMs", -1),
                Arguments.of("transmissionDateTime", null), Arguments.of("createdAt", null));
        return Stream.concat(required, constraints);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "{\"id\":\"not-a-uuid\"}"})
    void shouldRejectJsonWhenTransactionCannotBeDeserialized(String json) {
        //Given
        String body = json;
        //When
        Response response = given().spec(Api.request("logger")).body(body).when().post("/api/internal/log");
        //Then
        response.then().statusCode(400).body("error", equalTo("Invalid request body"))
                .body("serviceName", equalTo("transaction-logger")).body("message", containsString("malformed"));
    }

    @Test
    void shouldRejectMethodWhenLogEndpointIsReadInsteadOfWritten() {
        //Given
        String path = "/api/internal/log";
        //When
        Response response = given().spec(Api.request("logger")).when().get(path);
        //Then
        response.then().statusCode(405).body("serviceName", equalTo("transaction-logger"))
                .body("message", not(isEmptyOrNullString()));
    }
}
