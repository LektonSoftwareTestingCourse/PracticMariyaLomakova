package com.processing.api.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** HTTP-посредник для воспроизведения отказов, недоступных через публичный API. */
public final class HttpFaultProxy {
    private static final AtomicReference<Fault> FAULT = new AtomicReference<>();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    private HttpFaultProxy() { }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", HttpFaultProxy::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    private static void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (exchange.getRequestURI().getPath().equals("/__fault")) {
                configure(exchange, body);
                return;
            }
            Fault fault = FAULT.get();
            if (fault != null && exchange.getRequestMethod().equals(fault.method())
                    && exchange.getRequestURI().getPath().endsWith(fault.path())) {
                respond(exchange, fault.status(), fault.body(), "application/json");
                return;
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(
                    URI.create("http://card-management:8080" + exchange.getRequestURI()))
                    .timeout(Duration.ofSeconds(35))
                    .method(exchange.getRequestMethod(), HttpRequest.BodyPublishers.ofByteArray(body));
            request.header("Content-Type", "application/json");
            try {
                HttpResponse<byte[]> response = CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                respond(exchange, response.statusCode(), response.body(),
                        response.headers().firstValue("Content-Type").orElse("application/json"));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                respond(exchange, 503, "{}".getBytes(StandardCharsets.UTF_8), "application/json");
            } catch (IOException exception) {
                respond(exchange, 503, "{}".getBytes(StandardCharsets.UTF_8), "application/json");
            }
        }
    }

    private static void configure(HttpExchange exchange, byte[] body) throws IOException {
        if (exchange.getRequestMethod().equals("DELETE")) {
            FAULT.set(null);
        } else {
            Map<String, String> query = new HashMap<>();
            for (String pair : exchange.getRequestURI().getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                query.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            FAULT.set(new Fault(query.get("method"), query.get("path"), Integer.parseInt(query.get("status")), body));
        }
        respond(exchange, 200, "{\"configured\":true}".getBytes(StandardCharsets.UTF_8), "application/json");
    }

    private static void respond(HttpExchange exchange, int status, byte[] body, String contentType) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        exchange.getResponseBody().write(body);
    }

    private record Fault(String method, String path, int status, byte[] body) { }
}
