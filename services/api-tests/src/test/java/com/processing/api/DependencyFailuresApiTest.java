package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import com.processing.api.support.Stack;
import io.restassured.response.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

@Tag("fault")
class DependencyFailuresApiTest {
    @ParameterizedTest
    @CsvSource({"400,404,14,CARD_NOT_FOUND", "404,404,14,CARD_NOT_FOUND", "402,403,05,CARD_BLOCKED",
            "500,503,96,SERVICE_UNAVAILABLE", "503,503,96,SERVICE_UNAVAILABLE"})
    void shouldTranslateLookupFailureWhenDependencyReturnsError(int injected, int http, String code, String reason) {
        //Given
        String pan = Data.createCard().get("pan").toString();
        given().spec(Api.request("proxy")).queryParams(Map.of("method", "GET", "path", "/" + pan, "status", injected))
                .body("{}").when().post("/__fault").then().statusCode(200).body("configured", equalTo(true));
        try {
            //When
            Response response = given().spec(Api.request("authorization")).body(Data.authorization(pan, 1000))
                    .when().post("/api/internal/authorize");
            //Then
            response.then().statusCode(http).body("status", equalTo("DECLINED"))
                    .body("responseCode", equalTo(code)).body("declineReason", equalTo(reason));
        } finally {
            resetProxy();
        }
    }

    @ParameterizedTest
    @CsvSource({"400,404,14,CARD_NOT_FOUND", "404,404,14,CARD_NOT_FOUND", "402,422,51,INSUFFICIENT_FUNDS",
            "409,503,96,RESERVATION_FAILED", "500,503,96,SERVICE_UNAVAILABLE", "503,503,96,SERVICE_UNAVAILABLE"})
    void shouldTranslateReservationFailureWhenDependencyRejectsCommand(
            int injected, int http, String code, String reason) {
        //Given
        String pan = Data.createCard().get("pan").toString();
        given().spec(Api.request("proxy"))
                .queryParams(Map.of("method", "POST", "path", "/reserve", "status", injected))
                .body("{}").when().post("/__fault").then().statusCode(200).body("configured", equalTo(true));
        try {
            //When
            Response response = given().spec(Api.request("authorization")).body(Data.authorization(pan, 1000))
                    .when().post("/api/internal/authorize");
            //Then
            response.then().statusCode(http).body("status", equalTo("DECLINED"))
                    .body("responseCode", equalTo(code)).body("declineReason", equalTo(reason));
            assertThat(Data.balance(pan).intValueExact(), equalTo(1000000));
        } finally {
            resetProxy();
        }
    }

    @Test
    void shouldReturnUnknownReasonWhenDependencyReturnsCardWithoutStatus() {
        //Given
        Map<String, Object> card = Data.createCard();
        String pan = card.get("pan").toString();
        card.put("status", null);
        given().spec(Api.request("proxy")).queryParams(Map.of("method", "GET", "path", "/" + pan, "status", 200))
                .body(card).when().post("/__fault").then().statusCode(200).body("configured", equalTo(true));
        try {
            //When
            Response response = given().spec(Api.request("authorization")).body(Data.authorization(pan, 1000))
                    .when().post("/api/internal/authorize");
            //Then
            response.then().statusCode(400).body("responseCode", equalTo("05"))
                    .body("declineReason", equalTo("UNKNOWN_REASON")).body("status", equalTo("DECLINED"));
        } finally {
            resetProxy();
        }
    }

    @ParameterizedTest
    @CsvSource({"400,404,14,TRANSACTION_NOT_FOUND", "404,404,14,TRANSACTION_NOT_FOUND",
            "409,409,05,ALREADY_ROLLED_BACK", "500,503,96,SERVICE_UNAVAILABLE",
            "503,503,96,SERVICE_UNAVAILABLE", "502,503,96,ROLLBACK_FAILED"})
    void shouldTranslateRollbackFailureWhenDependencyRejectsCommand(int injected, int http, String code, String reason) {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Map<String, Object> body = Map.of("pan", pan, "rrn", "000000" + Data.stan(), "amount", 1000);
        given().spec(Api.request("proxy"))
                .queryParams(Map.of("method", "POST", "path", "/rollback", "status", injected))
                .body("{}").when().post("/__fault").then().statusCode(200).body("configured", equalTo(true));
        try {
            //When
            Response response = given().spec(Api.request("authorization")).body(body)
                    .when().post("/api/internal/rollback");
            //Then
            response.then().statusCode(http).body("status", equalTo("DECLINED"))
                    .body("declineReason", equalTo(reason)).body("responseCode", equalTo(code));
        } finally {
            resetProxy();
        }
    }

    @Test
    void shouldReturnDatabaseDeclineWhenOnlyAuthorizationStorageIsUnavailable() throws Exception {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Stack.command("stop", "authorization-postgres");
        try {
            //When
            Response response = given().spec(Api.request("authorization")).body(Data.authorization(pan, 1000))
                    .when().post("/api/internal/authorize");
            //Then
            // Ошибки БД при начале и завершении транзакции должны сохранять бизнес-отказ DB_UNAVAILABLE.
            response.then().statusCode(503).body("responseCode", equalTo("96"))
                    .body("declineReason", equalTo("DB_UNAVAILABLE")).body("status", equalTo("DECLINED"));
        } finally {
            Stack.command("start", "authorization-postgres");
            Stack.ready();
        }
    }

    @Test
    void shouldReturnRateLimitHeadersWhenClientHasNoTokens() throws Exception {
        //Given
        String pan = Data.createCard().get("pan").toString();
        try {
            Stack.gatewayRate(1, 0);
            given().spec(Api.request("gateway")).body(Data.authorization(pan, 1000)).when().post("/api/transactions")
                    .then().statusCode(200).body("status", equalTo("APPROVED"));
            //When
            Response response = given().spec(Api.request("gateway")).body(Data.authorization(pan, 1000))
                    .when().post("/api/transactions");
            //Then
            response.then().statusCode(429).header("Retry-After", equalTo("1"))
                    .body("error", equalTo("RATE_LIMIT_EXCEEDED")).body("retryAfterMs", equalTo(1000))
                    .body("message", equalTo("Too many requests. Try again later."));
        } finally {
            Stack.gatewayRate(100, 100);
        }
    }

    private void resetProxy() {
        given().spec(Api.request("proxy")).when().delete("/__fault")
                .then().statusCode(200).body("configured", equalTo(true));
    }
}
