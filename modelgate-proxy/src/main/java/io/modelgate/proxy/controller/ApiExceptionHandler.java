package io.modelgate.proxy.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import reactor.core.publisher.Mono;

import io.modelgate.client.UpstreamException;
import io.modelgate.proxy.service.ModelNotAllowedException;
import io.modelgate.proxy.service.NoSuchModelException;
import io.modelgate.proxy.service.QuotaExceededException;
import io.modelgate.quota.QuotaDecision;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NoSuchModelException.class)
    public Mono<ResponseEntity<ErrorBodies.ErrorBody>> noSuchModel(NoSuchModelException e) {
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorBodies.of(e.getMessage(), "invalid_request_error", 404)));
    }

    @ExceptionHandler(ModelNotAllowedException.class)
    public Mono<ResponseEntity<ErrorBodies.ErrorBody>> modelNotAllowed(ModelNotAllowedException e) {
        return Mono.just(ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ErrorBodies.of(e.getMessage(), "permission_error", 403)));
    }

    /** 429 carries Retry-After plus the dimension that rejected the request. */
    @ExceptionHandler(QuotaExceededException.class)
    public Mono<ResponseEntity<ErrorBodies.ErrorBody>> quotaExceeded(QuotaExceededException e) {
        QuotaDecision decision = e.decision();
        return Mono.just(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Long.toString(decision.retryAfterSeconds()))
                .header("x-ratelimit-scope", decision.rejectedScope().name().toLowerCase())
                .header("x-ratelimit-subject", decision.rejectedSubject())
                .body(ErrorBodies.of(e.getMessage(), "rate_limit_error", 429)));
    }

    @ExceptionHandler(UpstreamException.class)
    public Mono<ResponseEntity<ErrorBodies.ErrorBody>> upstream(UpstreamException e) {
        return Mono.just(ResponseEntity.status(e.status() >= 500 ? HttpStatus.BAD_GATEWAY
                        : HttpStatus.valueOf(e.status()))
                .body(ErrorBodies.of(e.getMessage(), "upstream_error", e.status())));
    }
}
