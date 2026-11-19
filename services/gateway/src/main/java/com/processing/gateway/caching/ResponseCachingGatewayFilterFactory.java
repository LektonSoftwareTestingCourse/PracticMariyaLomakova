package com.processing.gateway.caching;

import com.processing.gateway.metrics.GatewayMetrics;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.NettyWriteResponseFilter;
import org.springframework.cloud.gateway.filter.OrderedGatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class ResponseCachingGatewayFilterFactory
        extends AbstractGatewayFilterFactory<AbstractGatewayFilterFactory.NameConfig> {

    private static final String CACHE_HEADER_NAME = "X-Cache";
    private static final String CACHE_HIT = "HIT";
    private static final String CACHE_MISS = "MISS";

    private final Cache cache;
    private final GatewayMetrics gatewayMetrics;

    public ResponseCachingGatewayFilterFactory(
            Cache cache,
            GatewayMetrics gatewayMetrics) {
        super(NameConfig.class);
        this.cache = cache;
        this.gatewayMetrics = gatewayMetrics;
    }

    @Override
    public GatewayFilter apply(NameConfig config) {
        return new OrderedGatewayFilter((exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();

            if (request.getMethod() != HttpMethod.GET) {
                return chain.filter(exchange).doFinally(signalType -> {
                    if (exchange.getResponse().getStatusCode() == HttpStatus.OK
                        || exchange.getResponse().getStatusCode() == HttpStatus.CREATED
                        || exchange.getResponse().getStatusCode() == HttpStatus.NO_CONTENT) {
                        cache.clear();
                        gatewayMetrics.recordCardsCacheInvalidation();
                    }
                });
            }

            String requestKey = request.getPath() + request.getQueryParams().toString();
            byte[] cachedBody = cache.get(requestKey, byte[].class);

            if (cachedBody != null) {
                gatewayMetrics.recordCardsCacheHit();
                return writeCache(exchange, cachedBody);
            }

            gatewayMetrics.recordCardsCacheMiss();
            exchange.getResponse().getHeaders().add(CACHE_HEADER_NAME, CACHE_MISS);

            var decoratedResponse = new ResponseCachingDecorator(exchange.getResponse(), cache, requestKey);
            var mutatedExchange = exchange.mutate().response(decoratedResponse).build();

            return chain.filter(mutatedExchange);
        }, NettyWriteResponseFilter.WRITE_RESPONSE_FILTER_ORDER - 1);
    }

    private Mono<Void> writeCache(ServerWebExchange exchange, byte[] cachedBody) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.OK);
        response.getHeaders().add(CACHE_HEADER_NAME, CACHE_HIT);
        response.getHeaders().add(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);

        DataBuffer buffer = response.bufferFactory().wrap(cachedBody);

        return response.writeWith(Mono.just(buffer));
    }
}
