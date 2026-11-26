package com.processing.api;

import com.processing.api.support.Api;
import com.processing.api.support.Data;
import com.processing.api.support.Stack;
import io.restassured.response.Response;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.stream.Stream;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** Проверяет HTTP-ответы при отказе хранилища без прямого подключения тестов к нему. */
@Tag("fault")
class StorageStatusesApiTest {
    @ParameterizedTest(name = "{0} {1} {2}")
    @MethodSource("endpoints")
    void shouldReturnFailureBodyWhenPrimaryStorageIsUnavailable(
            String service, String method, String path, int status) throws Exception {
        //Given
        String pan = Data.createCard().get("pan").toString();
        String endpoint = path.replace("{pan}", pan);
        Map<String, Object> operation = Data.authorization(pan, 1000);
        operation.put("rrn", "000000" + Data.stan());
        operation.put("status", "BLOCKED");
        Map<String, Map<String, Object>> bodies = Map.of(
                "/api/cards", Data.cardBody(10000, 1000, 5000),
                "/api/cards/generate", Map.of("count", 1, "bins", java.util.List.of("400000")),
                "/api/cards/bulk-update", Map.of("status", "BLOCKED", "pans", java.util.List.of(pan)));
        Map<String, Object> body = bodies.getOrDefault(path, operation);
        Stack.command("stop", "postgres");
        try {
            //When
            Response response = given().spec(Api.request(service)).body(body).when().request(method, endpoint);
            //Then
            response.then().statusCode(status).body("error", not(isEmptyOrNullString()))
                    .body("message", not(isEmptyOrNullString())).body("serviceName", notNullValue());
        } finally {
            Stack.command("start", "postgres");
            Stack.ready();
        }
    }

    static Stream<Arguments> endpoints() {
        return Stream.of(Arguments.of("cards", "POST", "/api/cards", 500),
                Arguments.of("cards", "POST", "/api/cards/generate", 500),
                Arguments.of("cards", "GET", "/api/cards", 500),
                Arguments.of("cards", "GET", "/api/cards/{pan}", 500),
                Arguments.of("cards", "PATCH", "/api/cards/{pan}", 500),
                Arguments.of("cards", "DELETE", "/api/cards/{pan}", 500),
                Arguments.of("cards", "POST", "/api/cards/{pan}/reserve", 500),
                Arguments.of("cards", "POST", "/api/cards/{pan}/rollback", 500),
                Arguments.of("cards", "POST", "/api/cards/bulk-update", 500),
                Arguments.of("logger", "GET", "/api/transactions/search", 503),
                Arguments.of("logger", "GET", "/api/transactions/export", 503),
                Arguments.of("logger", "GET", "/api/dashboard/stats", 503),
                Arguments.of("logger", "GET", "/api/dashboard/recent", 503),
                Arguments.of("logger", "GET", "/api/dashboard/charts", 503),
                Arguments.of("merchant", "GET", "/api/simulator/merchants", 500),
                Arguments.of("merchant", "POST", "/api/simulator/merchant/fee", 500));
    }
}
