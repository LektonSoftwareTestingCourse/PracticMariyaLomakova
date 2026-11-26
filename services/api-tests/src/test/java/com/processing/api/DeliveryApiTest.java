package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

class DeliveryApiTest {
    @Test
    void shouldFindStoredTransactionWhenSubmittedThroughGateway() {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Map<String, Object> body = Data.authorization(pan, 1000);
        //When
        Response response = given().spec(Api.request("gateway")).body(body).when().post("/api/transactions");
        //Then
        response.then().statusCode(200).body("status", equalTo("APPROVED")).body("responseCode", equalTo("00"));
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                Data.search(pan).then().statusCode(200).body("transactions.findAll { it.stan == '"
                        + body.get("stan") + "' && it.rrn == '" + response.path("rrn") + "' }", hasSize(1))
                        .body("transactions[0].amount", equalTo(1000))
                        .body("transactions[0].issuerId", equalTo("ISS001")));
    }

    @Test
    void shouldDocumentRepeatedReservationWhenSameStanIsAuthorizedAgain() {
        //Given
        String pan = Data.createCard().get("pan").toString();
        Map<String, Object> body = Data.authorization(pan, 1000);
        String firstRrn = given().spec(Api.request("gateway")).body(body).when().post("/api/transactions")
                .then().statusCode(200).body("status", equalTo("APPROVED")).extract().path("rrn");
        //When
        Response response = given().spec(Api.request("gateway")).body(body).when().post("/api/transactions");
        //Then
        // Известное ограничение D04: авторизация не устраняет повторы по STAN.
        response.then().statusCode(200).body("status", equalTo("APPROVED")).body("rrn", not(equalTo(firstRrn)));
        assertThat(Data.balance(pan).intValueExact(), equalTo(998000));
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(500)).untilAsserted(() ->
                Data.search(pan).then().statusCode(200)
                        .body("transactions.findAll { it.stan == '" + body.get("stan") + "' }", hasSize(2)));
    }
}
