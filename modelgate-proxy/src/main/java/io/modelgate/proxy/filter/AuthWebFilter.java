package io.modelgate.proxy.filter;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

import io.modelgate.proxy.config.SecurityProperties;
import io.modelgate.proxy.controller.ErrorBodies;

/**
 * Bearer-token check for the public API (/v1/**). W1: static key list;
 * W2: hashed keys in MySQL behind a local+Redis cache with in-flight merging.
 */
@Component
@Order(-100)
public class AuthWebFilter implements WebFilter {

    private static final String UNAUTHORIZED_BODY = "{\"error\":{\"message\":"
            + "\"Missing or invalid API key. Expected header: Authorization: Bearer <key>\","
            + "\"type\":\"auth_error\",\"code\":401}}";

    private final Set<String> apiKeys;

    public AuthWebFilter(SecurityProperties properties) {
        this.apiKeys = Set.copyOf(properties.getApiKeys());
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/v1/")) {
            return chain.filter(exchange);
        }
        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        boolean authorized = auth != null
                && auth.startsWith("Bearer ")
                && apiKeys.contains(auth.substring(7).trim());
        if (!authorized) {
            ServerHttpResponse response = exchange.getResponse();
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
            DataBuffer buffer = response.bufferFactory()
                    .wrap(UNAUTHORIZED_BODY.getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(buffer));
        }
        return chain.filter(exchange);
    }
}
