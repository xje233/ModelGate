package io.modelgate.client;

/**
 * An upstream call failed. {@code retryable} follows the LiteLLM convention:
 * 5xx / 429 / timeouts are retryable (and count towards cooldown), other
 * 4xx are not (retrying a 400 would just fail again).
 */
public final class UpstreamException extends RuntimeException {

    private final int status;
    private final boolean retryable;

    public UpstreamException(int status, String message, boolean retryable) {
        super(message);
        this.status = status;
        this.retryable = retryable;
    }

    public int status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }

    public static boolean isRetryableStatus(int status) {
        return status >= 500 || status == 429;
    }
}
