package io.modelgate.proxy.controller;

/** OpenAI-style error payloads. */
public final class ErrorBodies {

    public record Error(String message, String type, Integer code) {
    }

    public record ErrorBody(Error error) {
    }

    private ErrorBodies() {
    }

    public static ErrorBody of(String message, String type, Integer code) {
        return new ErrorBody(new Error(message, type, code));
    }
}
