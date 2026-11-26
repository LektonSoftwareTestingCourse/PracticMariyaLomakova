package com.processing.api.support;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.config.HttpClientConfig;
import io.restassured.config.RestAssuredConfig;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

import java.util.Map;

/** HTTP-адреса соответствуют отдельному тестовому стенду из compose.yaml. */
public final class Api {
    private static final Map<String, Integer> PORTS = Map.of(
            "gateway", 18080, "cards", 18081, "switch", 18082, "authorization", 18083,
            "merchant", 18084, "terminal", 18085, "logger", 18088, "bin", 18096, "proxy", 18099);

    private Api() { }

    public static RequestSpecification request(String service) {
        return new RequestSpecBuilder()
                .setBaseUri(System.getProperty("api." + service + ".url", "http://localhost:" + PORTS.get(service)))
                .setContentType(ContentType.JSON)
                .setAccept(ContentType.JSON)
                .setConfig(RestAssuredConfig.config().httpClient(HttpClientConfig.httpClientConfig()
                        .setParam("http.connection.timeout", 3000).setParam("http.socket.timeout", 30000)))
                .build();
    }
}
