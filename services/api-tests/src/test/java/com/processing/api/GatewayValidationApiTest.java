package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

class GatewayValidationApiTest {
    @ParameterizedTest(name = "missing {0}")
    @ValueSource(strings = {"mti", "stan", "pan", "processingCode", "amount", "currencyCode",
            "transmissionDateTime", "terminalId", "merchantId", "mcc", "acquirerId"})
    void shouldIdentifyRequiredFieldWhenItIsAbsent(String field) {
        //Given
        Map<String, Object> body = Data.authorization("4000000000000002", 1000);
        body.remove(field);
        //When
        Response response = given().spec(Api.request("gateway")).body(body).when().post("/api/transactions");
        //Then
        response.then().statusCode(400).body("error", equalTo("VALIDATION_ERROR"))
                .body("message", equalTo("Field '" + field + "' is required"))
                .body("serviceName", equalTo("gateway")).body("timestamp", notNullValue())
                .body("retryAfterMs", nullValue());
    }

    @ParameterizedTest(name = "{0}={1}")
    @MethodSource("invalidFields")
    void shouldExplainRuleWhenOneFieldHasInvalidValue(String field, Object value, String message) {
        //Given
        Map<String, Object> body = Data.authorization("4000000000000002", 1000);
        body.put(field, value);
        //When
        Response response = given().spec(Api.request("gateway")).body(body).when().post("/api/transactions");
        //Then
        response.then().statusCode(400).body("error", equalTo("VALIDATION_ERROR"))
                .body("message", equalTo(message)).body("serviceName", equalTo("gateway"));
    }

    static Stream<Arguments> invalidFields() {
        Stream<Arguments> required = Stream.of("mti", "stan", "pan", "processingCode", "currencyCode",
                "terminalId", "merchantId", "mcc", "acquirerId")
                .flatMap(field -> Stream.of(null, "", " ").map(value ->
                        Arguments.of(field, value, "Field '" + field + "' is required")));
        Stream<Arguments> formats = Stream.of(
                Arguments.of("mti", "0200", "Field 'mti' must be '0100'"),
                Arguments.of("pan", "400000000000000", "Field 'pan' must be exactly 16 digits"),
                Arguments.of("pan", "40000000000000000", "Field 'pan' must be exactly 16 digits"),
                Arguments.of("pan", "400000000000000A", "Field 'pan' must be exactly 16 digits"),
                Arguments.of("amount", 0, "Field 'amount' must be > 0"),
                Arguments.of("amount", -1, "Field 'amount' must be > 0"),
                Arguments.of("amount", null, "Field 'amount' is required"),
                Arguments.of("currencyCode", "64", "Field 'currencyCode' must be exactly 3 characters"),
                Arguments.of("currencyCode", "6430", "Field 'currencyCode' must be exactly 3 characters"),
                Arguments.of("mcc", "541", "Field 'mcc' must be exactly 4 digits"),
                Arguments.of("mcc", "54111", "Field 'mcc' must be exactly 4 digits"),
                Arguments.of("mcc", "541A", "Field 'mcc' must be exactly 4 digits"));
        return Stream.concat(required, formats);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[]", "null"})
    void shouldRejectBodyWhenItIsMalformedOrMissing(String body) {
        //Given
        String requestBody = body;
        //When
        Response response = given().spec(Api.request("gateway")).body(requestBody).when().post("/api/transactions");
        //Then
        response.then().statusCode(400).body("error", equalTo("VALIDATION_ERROR"))
                .body("message", oneOf("Request body must be valid JSON", "Request body is required"))
                .body("serviceName", equalTo("gateway"));
    }

    @Test
    void shouldAcceptOptionalFieldsWhenIssuerAndTerminalTypeAreAbsent() {
        //Given
        Map<String, Object> body = Data.authorization(Data.createCard().get("pan").toString(), 1000);
        body.remove("issuerId");
        body.remove("terminalType");
        //When
        Response response = given().spec(Api.request("gateway")).body(body).when().post("/api/transactions");
        //Then
        response.then().statusCode(200).body("status", equalTo("APPROVED")).body("responseCode", equalTo("00"));
    }

    @Test
    void shouldRejectDateWhenOffsetIsMissing() {
        //Given
        Map<String, Object> body = Data.authorization("4000000000000002", 1000);
        body.put("transmissionDateTime", "2026-06-01T12:00:00");
        //When
        Response response = given().spec(Api.request("gateway")).body(body).when().post("/api/transactions");
        //Then
        response.then().statusCode(400).body("error", equalTo("VALIDATION_ERROR"))
                .body("message", equalTo("Request body must be valid JSON")).body("serviceName", equalTo("gateway"));
    }
}
