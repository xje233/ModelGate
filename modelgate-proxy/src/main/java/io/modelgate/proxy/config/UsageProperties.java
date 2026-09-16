package io.modelgate.proxy.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * modelgate.usage.* — where receipts go.
 *
 * <p>{@code memory} needs nothing and survives no restart; {@code jdbc} writes to
 * {@code t_usage_log} asynchronously in batches (H2 locally, MySQL in production — same SQL).
 */
@ConfigurationProperties(prefix = "modelgate.usage")
public class UsageProperties {

    private String backend = "memory";

    /** receipts kept by the in-memory store */
    private int memoryMaxRecords = 20_000;

    /** bounded queue between the request path and the writer */
    private int queueCapacity = 20_000;

    /** records per batch insert */
    private int batchSize = 500;

    /** how often the writer drains the queue */
    private long flushIntervalMillis = 1_000;

    public String getBackend() {
        return backend;
    }

    public void setBackend(String backend) {
        this.backend = backend;
    }

    public int getMemoryMaxRecords() {
        return memoryMaxRecords;
    }

    public void setMemoryMaxRecords(int memoryMaxRecords) {
        this.memoryMaxRecords = memoryMaxRecords;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public long getFlushIntervalMillis() {
        return flushIntervalMillis;
    }

    public void setFlushIntervalMillis(long flushIntervalMillis) {
        this.flushIntervalMillis = flushIntervalMillis;
    }
}
