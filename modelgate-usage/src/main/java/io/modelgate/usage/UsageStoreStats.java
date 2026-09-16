package io.modelgate.usage;

/**
 * Accounting health. {@code dropped} is the number worth alerting on: it means requests were
 * served but never billed.
 */
public record UsageStoreStats(long queued, long written, long dropped) {
}
