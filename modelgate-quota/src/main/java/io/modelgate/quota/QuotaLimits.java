package io.modelgate.quota;

/**
 * Per-minute limits of one dimension. A negative value means "not limited".
 *
 * @param rpm requests per minute (sliding window)
 * @param tpm tokens per minute (fixed window, anchored at the first token of the window)
 */
public record QuotaLimits(int rpm, long tpm) {

    public static final QuotaLimits UNLIMITED = new QuotaLimits(-1, -1);

    public boolean rpmUnlimited() {
        return rpm < 0;
    }

    public boolean tpmUnlimited() {
        return tpm < 0;
    }

    public boolean unlimited() {
        return rpmUnlimited() && tpmUnlimited();
    }

    public static QuotaLimits of(int rpm, long tpm) {
        return new QuotaLimits(rpm, tpm);
    }
}
