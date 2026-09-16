package io.modelgate.mock;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** mock.* — the tunables that make this upstream useful for load testing. */
@ConfigurationProperties(prefix = "mock")
public class MockProperties {

    /** instance name, appears in replies so routing distribution is visible */
    private String name = "mock";

    /** simulated upstream latency for non-streaming calls (ms) */
    private long p50Ms = 80;

    /** time-to-first-token for streaming calls (ms) */
    private long ttftMs = 60;

    /** gap between streamed chunks (ms) — token-level pacing */
    private long chunkIntervalMs = 15;

    /** how many chunks a streaming reply is split into */
    private int replyChunks = 8;

    /** baseline random failure rate (0..1), can be overridden per request by header */
    private double errorRate = 0.0;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getP50Ms() {
        return p50Ms;
    }

    public void setP50Ms(long p50Ms) {
        this.p50Ms = p50Ms;
    }

    public long getTtftMs() {
        return ttftMs;
    }

    public void setTtftMs(long ttftMs) {
        this.ttftMs = ttftMs;
    }

    public long getChunkIntervalMs() {
        return chunkIntervalMs;
    }

    public void setChunkIntervalMs(long chunkIntervalMs) {
        this.chunkIntervalMs = chunkIntervalMs;
    }

    public int getReplyChunks() {
        return replyChunks;
    }

    public void setReplyChunks(int replyChunks) {
        this.replyChunks = replyChunks;
    }

    public double getErrorRate() {
        return errorRate;
    }

    public void setErrorRate(double errorRate) {
        this.errorRate = errorRate;
    }
}
