package io.modelgate.proxy.service;

/** The requested model group is not configured on this gateway. */
public final class NoSuchModelException extends RuntimeException {

    public NoSuchModelException(String model) {
        super("model not found: " + model);
    }
}
