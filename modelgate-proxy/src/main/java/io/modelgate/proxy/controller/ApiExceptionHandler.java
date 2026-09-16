package io.modelgate.proxy.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import reactor.core.publisher.Mono;

import io.modelgate.client.UpstreamException;
import io.modelgate.proxy.service.NoSuchModelException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(NoSuchModelException.class)
    public Mono<ResponseEntity<ErrorBodies.ErrorBody>> noSuchModel(NoSuchModelException e) {
        return Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ErrorBodies.of(e.getMessage(), "invalid_request_error", 404)));
    }

    @ExceptionHandler(UpstreamException.class)
    public Mono<ResponseEntity<ErrorBodies.ErrorBody>> upstream(UpstreamException e) {
        return Mono.just(ResponseEntity.status(e.status() >= 500 ? HttpStatus.BAD_GATEWAY
                        : HttpStatus.valueOf(e.status()))
                .body(ErrorBodies.of(e.getMessage(), "upstream_error", e.status())));
    }
}
