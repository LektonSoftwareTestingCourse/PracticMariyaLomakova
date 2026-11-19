package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class AuthorizationApiTest {
    @ParameterizedTest
    @ValueSource(strings = {"gateway", "switch", "authorization"})
    void shouldApproveAndReserveWhenCardAndRequestAreValid(String service) {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Map<String, Object> body = Data.authorization(pan, 1000);
        String path = Map.of("gateway", "/api/transactions", "switch", "/api/internal/route",
                "authorization", "/api/internal/authorize").get(service);
        //When
        Response response = given().spec(Api.request(service)).body(body).when().post(path);
        //Then
        response.then().statusCode(200).contentType("application/json")
                .body(matchesJsonSchemaInClasspath("schemas/authorization.json"))
                // D01: ответ повторяет MTI запроса 0100 вместо ожидаемого 0110.
                .body("mti", equalTo("0100"))
                .body("stan", equalTo(body.get("stan"))).body("status", equalTo("APPROVED"))
                .body("responseCode", equalTo("00")).body("rrn", matchesPattern("\\d{12}"))
                .body("authCode", matchesPattern("[A-Z0-9]{6}"))
                .body("processingTimeMs", greaterThanOrEqualTo(0)).body("declineReason", nullValue());
        assertThat(response.jsonPath().getMap("$").containsKey("declineReason"), is(true));
        assertThat(Data.balance(pan).intValueExact(), equalTo(999000));
    }

    @ParameterizedTest
    @CsvSource({"BLOCKED, 05, CARD_BLOCKED", "INACTIVE, 05, CARD_INACTIVE", "EXPIRED, 54, CARD_EXPIRED"})
    void shouldPreserveBusinessDeclineWhenCardHasInactiveStatus(String status, String code, String reason) {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Data.patch(pan, Map.of("status", status));
        Map<String, Object> body = Data.authorization(pan, 1000);
        //When
        Response response = given().spec(Api.request("gateway")).body(body).when().post("/api/transactions");
        //Then
        response.then().statusCode(200).body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo(code)).body("declineReason", equalTo(reason))
                .body("rrn", nullValue()).body("authCode", nullValue());
        given().spec(Api.request("authorization")).body(body).when().post("/api/internal/authorize")
                .then().statusCode(403).body("responseCode", equalTo(code)).body("declineReason", equalTo(reason));
        assertThat(Data.balance(pan).intValueExact(), equalTo(1000000));
    }

    @Test
    void shouldDeclineForExpiryWhenTransactionOccursAfterLastValidDay() {
        //Given
        Map<String, Object> card = Data.createCard();
        String pan = card.get("pan").toString();
        Map<String, Object> body = Data.authorization(pan, 1000);
        YearMonth expiry = YearMonth.parse(card.get("expiryDate").toString());
        body.put("transmissionDateTime", expiry.plusMonths(1).atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC).toString());
        //When
        Response response = given().spec(Api.request("gateway")).body(body).when().post("/api/transactions");
        //Then
        response.then().statusCode(200).body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("54")).body("declineReason", equalTo("CARD_EXPIRED"));
        assertThat(Data.balance(pan).intValueExact(), equalTo(1000000));
    }

    @ParameterizedTest
    @ValueSource(strings = {"400000", "999999"})
    void shouldDeclineWhenCardIsUnknownThroughKnownOrUnknownBin(String bin) {
        //Given
        String pan = bin + String.format("%010d", Math.floorMod(System.nanoTime(), 10000000000L));
        Map<String, Object> body = Data.authorization(pan, 1000);
        //When
        Response response = given().spec(Api.request("gateway")).body(body).when().post("/api/transactions");
        //Then
        response.then().statusCode(200).body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("CARD_NOT_FOUND")).body("status", equalTo("DECLINED"));
    }

    @Test
    void shouldReturnUnprocessableEntityWhenInternalBalanceIsInsufficient() {
        //Given
        String pan = Data.createCard(1000, 100000, 500000).get("pan").toString();
        Map<String, Object> body = Data.authorization(pan, 1001);
        //When
        Response response = given().spec(Api.request("authorization")).body(body).when().post("/api/internal/authorize");
        //Then
        response.then().statusCode(422).body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("51")).body("declineReason", equalTo("INSUFFICIENT_FUNDS"));
        assertThat(Data.balance(pan).intValueExact(), equalTo(1000));
    }

    @ParameterizedTest
    @CsvSource({"999, APPROVED, 00", "1000, APPROVED, 00", "1001, DECLINED, 51"})
    void shouldApplyBalanceBoundaryWhenAuthorizing(int amount, String status, String code) {
        //Given
        String pan = Data.createCard(1000, 100000, 500000).get("pan").toString();
        //When
        Response response = given().spec(Api.request("gateway")).body(Data.authorization(pan, amount))
                .when().post("/api/transactions");
        //Then
        response.then().statusCode(200).body("status", equalTo(status)).body("responseCode", equalTo(code));
    }

    @Test
    void shouldDeclineWhenAccumulatedDailyLimitWouldBeExceeded() {
        //Given
        String pan = Data.createCard(10000, 1000, 5000).get("pan").toString();
        given().spec(Api.request("gateway")).body(Data.authorization(pan, 1000)).when().post("/api/transactions")
                .then().statusCode(200).body("status", equalTo("APPROVED"));
        //When
        Response response = given().spec(Api.request("gateway")).body(Data.authorization(pan, 1))
                .when().post("/api/transactions");
        //Then
        response.then().statusCode(200).body("responseCode", equalTo("61"))
                .body("declineReason", equalTo("EXCEEDS_AMOUNT_LIMIT")).body("status", equalTo("DECLINED"));
        assertThat(Data.balance(pan).intValueExact(), equalTo(9000));
    }

    @Test
    void shouldDeclineWhenAccumulatedMonthlyLimitWouldBeExceededAcrossDays() {
        //Given
        String pan = Data.createCard(10000, 1000, 1500).get("pan").toString();
        Map<String, Object> first = Data.authorization(pan, 1000);
        first.put("transmissionDateTime", "2026-06-01T12:00:00Z");
        given().spec(Api.request("gateway")).body(first).when().post("/api/transactions")
                .then().statusCode(200).body("status", equalTo("APPROVED"));
        Map<String, Object> second = Data.authorization(pan, 501);
        second.put("transmissionDateTime", "2026-06-02T12:00:00Z");
        //When
        Response response = given().spec(Api.request("gateway")).body(second).when().post("/api/transactions");
        //Then
        response.then().statusCode(200).body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("61")).body("declineReason", equalTo("EXCEEDS_AMOUNT_LIMIT"));
    }

    @Test
    void shouldRollbackAndRejectRepeatWhenAuthorizationReservationExists() {
        //Given
        String pan = Data.createCard().get("pan").toString();
        String rrn = given().spec(Api.request("authorization")).body(Data.authorization(pan, 1000))
                .when().post("/api/internal/authorize").then().statusCode(200)
                .body("status", equalTo("APPROVED")).extract().path("rrn");
        Map<String, Object> body = Map.of("pan", pan, "rrn", rrn, "amount", 1000);
        //When
        Response response = given().spec(Api.request("authorization")).body(body).when().post("/api/internal/rollback");
        //Then
        response.then().statusCode(200).body("status", equalTo("APPROVED")).body("rrn", equalTo(rrn));
        assertThat(Data.balance(pan).intValueExact(), equalTo(1000000));
        given().spec(Api.request("authorization")).body(body).when().post("/api/internal/rollback")
                .then().statusCode(409).body("responseCode", equalTo("05"))
                .body("declineReason", equalTo("ALREADY_ROLLED_BACK"));
    }

    @Test
    void shouldReturnNotFoundWhenRollbackReservationDoesNotExist() {
        //Given
        String pan = Data.createCard().get("pan").toString();
        //When
        Response response = given().spec(Api.request("authorization"))
                .body(Map.of("pan", pan, "rrn", "000000" + Data.stan(), "amount", 1000))
                .when().post("/api/internal/rollback");
        //Then
        response.then().statusCode(404).body("status", equalTo("DECLINED"))
                .body("responseCode", equalTo("14"))
                .body("declineReason", equalTo("TRANSACTION_NOT_FOUND"));
    }
}
