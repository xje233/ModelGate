package io.modelgate.proxy.service;

/** The key's model allow-list does not contain the requested model group. */
public final class ModelNotAllowedException extends RuntimeException {

    public ModelNotAllowedException(String model, String keyId) {
        super("key '" + keyId + "' is not allowed to use model '" + model + "'");
    }
}
