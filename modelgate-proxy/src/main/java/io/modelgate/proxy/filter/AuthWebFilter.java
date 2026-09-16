package io.modelgate.proxy.filter;

import java.nio.charset.StandardCharsets;

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

import io.modelgate.proxy.security.ApiKeyIdentity;
import io.modelgate.proxy.security.ApiKeyRegistry;

/**
 * Bearer-token authentication for the public API (/v1/**). On success the resolved identity
 * is attached to the exchange so the quota layer and the controller share one lookup.
 */
@Component
@Order(-100)
public class AuthWebFilter implements WebFilter {

    private static final String UNAUTHORIZED_BODY = "{\"error\":{\"message\":"
            + "\"Missing or invalid API key. Expected header: Authorization: Bearer <key>\","
            + "\"type\":\"auth_error\",\"code\":401}}";

    private final ApiKeyRegistry registry;

    public AuthWebFilter(ApiKeyRegistry registry) {
        this.registry = registry;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.startsWith("/v1/")) {
            return chain.filter(exchange);
        }
        String token = bearerToken(exchange);
        ApiKeyIdentity identity = registry.find(token);
        if (identity == null) {
            return unauthorized(exchange.getResponse());
        }
        exchange.getAttributes().put(ApiKeyIdentity.ATTRIBUTE, identity);
        return chain.filter(exchange);
    }

    private static String bearerToken(ServerWebExchange exchange) {
        String auth = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            return null;
        }
        return auth.substring(7).trim();
    }

    private static Mono<Void> unauthorized(ServerHttpResponse response) {
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory()
                .wrap(UNAUTHORIZED_BODY.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
