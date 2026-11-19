package com.processing.api.support;

import io.restassured.response.Response;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.matchesPattern;

/** Тестовые данные создаются через HTTP. Суммы задаются в минимальных единицах валюты. */
public final class Data {
    private static final AtomicInteger SEQUENCE = new AtomicInteger((int) Math.floorMod(System.nanoTime(), 900000L));

    private Data() { }

    public static String stan() {
        return String.format("%06d", SEQUENCE.incrementAndGet() % 1000000);
    }

    public static Map<String, Object> cardBody(long balance, long daily, long monthly) {
        return new LinkedHashMap<>(Map.of("bin", "400000", "cardholderName", "API TEST",
                "currencyCode", "643", "initialBalance", balance, "dailyLimit", daily, "monthlyLimit", monthly));
    }

    public static Map<String, Object> createCard(long balance, long daily, long monthly) {
        return given().spec(Api.request("gateway")).body(cardBody(balance, daily, monthly))
                .when().post("/api/cards")
                .then().statusCode(201).body("pan", matchesPattern("400000\\d{10}"))
                .body("status", equalTo("ACTIVE")).extract().jsonPath().getMap("$");
    }

    public static Map<String, Object> createCard() {
        return createCard(1000000, 100000, 500000);
    }

    public static void patch(String pan, Map<String, ?> fields) {
        given().spec(Api.request("gateway")).body(fields).when().patch("/api/cards/" + pan)
                .then().statusCode(200).body("pan", equalTo(pan));
    }

    public static Map<String, Object> authorization(String pan, long amount) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mti", "0100");
        body.put("stan", stan());
        body.put("pan", pan);
        body.put("processingCode", "000000");
        body.put("amount", amount);
        body.put("currencyCode", "643");
        body.put("transmissionDateTime", Instant.now().toString());
        body.put("terminalId", "TERM0001");
        body.put("terminalType", "POS");
        body.put("merchantId", "MERCH0000000001");
        body.put("mcc", "5411");
        body.put("acquirerId", "ACQ001");
        body.put("issuerId", "ISS001");
        return body;
    }

    public static Map<String, Object> transaction(String pan) {
        Map<String, Object> body = authorization(pan, 1000);
        body.put("id", UUID.randomUUID().toString());
        body.put("rrn", "000000" + stan());
        body.put("status", "APPROVED");
        body.put("declineReason", null);
        body.put("authCode", "TEST01");
        body.put("processingTimeMs", 0);
        body.put("acquiringFee", 0);
        body.put("createdAt", Instant.now().toString());
        return body;
    }

    public static void store(Map<String, Object> transaction) {
        given().spec(Api.request("logger")).body(transaction).when().post("/api/internal/log")
                .then().statusCode(201).body("id", equalTo(transaction.get("id"))).body("status", equalTo("stored"));
    }

    public static Response search(String pan) {
        return given().spec(Api.request("gateway")).queryParam("pan", pan)
                .when().get("/api/transactions/search");
    }

    public static BigDecimal balance(String pan) {
        Response response = given().spec(Api.request("cards")).when().get("/api/cards/" + pan);
        response.then().statusCode(200).body("pan", equalTo(pan));
        return new BigDecimal(response.jsonPath().getString("availableBalance"));
    }
}
