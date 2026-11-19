package com.processing.api.support;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;

/** Сценарии отказов управляют только отдельным тестовым проектом Docker Compose. */
public final class Stack {
    private Stack() { }

    public static void command(String action, String service) throws IOException, InterruptedException {
        String file = Path.of(System.getProperty("api.compose.file", "compose.yaml")).toAbsolutePath().toString();
        String project = System.getProperty("api.compose.project", "smp-api-tests");
        Process process = new ProcessBuilder("docker", "compose", "-p", project, "-f", file, action, service)
                .inheritIO().start();
        if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Compose command timed out for " + service);
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Compose command failed for " + service);
        }
    }

    public static void ready() {
        await().atMost(Duration.ofSeconds(120)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions().untilAsserted(() -> {
            given().spec(Api.request("gateway")).when().get("/health")
                    .then().statusCode(200).body("status", equalTo("ok"));
            given().spec(Api.request("authorization")).when().get("/actuator/health")
                    .then().statusCode(200).body("status", equalTo("UP"));
            given().spec(Api.request("logger")).when().get("/actuator/health")
                    .then().statusCode(200).body("status", equalTo("UP"));
            given().spec(Api.request("cards")).when().get("/actuator/health")
                    .then().statusCode(200).body("status", equalTo("UP"));
            given().spec(Api.request("merchant")).when().get("/actuator/health")
                    .then().statusCode(200).body("status", equalTo("UP"));
            given().spec(Api.request("terminal")).when().get("/actuator/health")
                    .then().statusCode(200).body("status", equalTo("UP"));
            given().spec(Api.request("switch")).when().get("/actuator/health")
                    .then().statusCode(200).body("status", equalTo("UP"));
        });
    }

    /** Готовность процесса не означает, что защитная цепь Gateway уже замкнута. */
    public static void gatewayReady(String serviceName, String pan) {
        Probe probe = Map.of(
                "cardManagement", new Probe("GET", "/api/cards/" + pan, Map.of()),
                "logger", new Probe("GET", "/api/dashboard/stats", Map.of()),
                "switch", new Probe("POST", "/api/transactions", Data.authorization(pan, 1)),
                "terminalSimulator", new Probe("POST", "/api/simulator/terminal/stop", Map.of()),
                "merchantSimulator", new Probe("POST", "/api/simulator/merchant/run",
                        Map.of("count", 1, "scenario", "grocery", "tps", 1))).get(serviceName);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofSeconds(1)).ignoreExceptions()
                .untilAsserted(() -> given().spec(Api.request("gateway")).body(probe.body())
                        .when().request(probe.method(), probe.path()).then().statusCode(200));
    }

    public static void gatewayRate(int capacity, int refill) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder("docker", "compose", "-p",
                System.getProperty("api.compose.project", "smp-api-tests"), "-f",
                Path.of(System.getProperty("api.compose.file", "compose.yaml")).toAbsolutePath().toString(),
                "up", "-d", "--no-deps", "--force-recreate", "gateway").inheritIO();
        builder.environment().put("API_RATE_CAPACITY", Integer.toString(capacity));
        builder.environment().put("API_RATE_REFILL", Integer.toString(refill));
        Process process = builder.start();
        if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IllegalStateException("Could not configure gateway rate limit");
        }
        ready();
    }

    private record Probe(String method, String path, Map<String, Object> body) { }
}
