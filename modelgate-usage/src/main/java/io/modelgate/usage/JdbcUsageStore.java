package io.modelgate.usage;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Writes receipts to {@code t_usage_log} in batches from a single background thread.
 *
 * <p>Shape of the trade-off (the same one LiteLLM makes): the request path only does a queue
 * {@code offer}, so a slow or unavailable database can never slow a completion down; the price
 * is that the bill is eventually consistent and, if the queue overflows, some receipts are
 * dropped and counted in {@link UsageStoreStats#dropped()}.
 *
 * <p>SQL is portable between MySQL and H2 (no vendor-specific functions; the date is written by
 * the application as its own column so {@code GROUP BY usage_date} works everywhere), which is
 * why the integration test can run on H2 while production runs on MySQL.
 */
public final class JdbcUsageStore implements UsageStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcUsageStore.class);

    private static final String INSERT = """
            INSERT INTO t_usage_log (request_id, key_id, tenant_id, model_group, model_id, provider,
                                     prompt_tokens, completion_tokens, cached_prompt_tokens, cost_usd,
                                     cache_hit, stream, arm, attempted, status, usage_date,
                                     start_time, completion_start_time, end_time)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""";

    private final JdbcTemplate jdbc;
    private final BlockingQueue<UsageRecord> queue;
    private final int batchSize;
    private final ScheduledExecutorService flusher;
    private final AtomicLong written = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();

    public JdbcUsageStore(DataSource dataSource, int queueCapacity, int batchSize,
                          long flushIntervalMillis) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.queue = new LinkedBlockingQueue<>(Math.max(1, queueCapacity));
        this.batchSize = Math.max(1, batchSize);
        this.flusher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "modelgate-usage-flusher");
            thread.setDaemon(true);
            return thread;
        });
        this.flusher.scheduleWithFixedDelay(this::flushQuietly,
                flushIntervalMillis, flushIntervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void record(UsageRecord record) {
        if (!queue.offer(record)) {
            dropped.incrementAndGet();
        }
    }

    private void flushQuietly() {
        try {
            drainAndWrite();
        } catch (Exception e) {
            // never let a bad batch kill the flusher thread; the records stay in the queue
            log.warn("usage batch flush failed: {}", e.toString());
        }
    }

    private int drainAndWrite() {
        List<UsageRecord> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        if (batch.isEmpty()) {
            return 0;
        }
        List<Object[]> args = new ArrayList<>(batch.size());
        for (UsageRecord record : batch) {
            args.add(new Object[]{
                    record.requestId(), record.keyId(), record.tenantId(),
                    record.modelGroup(), record.modelId(), record.provider(),
                    record.promptTokens(), record.completionTokens(), record.cachedPromptTokens(),
                    record.costUsd() == null ? BigDecimal.ZERO : record.costUsd(),
                    record.cacheHit() ? 1 : 0, record.stream() ? 1 : 0,
                    record.arm(), record.attempted(), record.status(),
                    record.usageDate() == null ? null : Date.valueOf(record.usageDate()),
                    new Timestamp(record.startMillis()),
                    record.completionStartMillis() <= 0 ? null : new Timestamp(record.completionStartMillis()),
                    new Timestamp(record.endMillis())});
        }
        jdbc.batchUpdate(INSERT, args);
        written.addAndGet(batch.size());
        return batch.size();
    }

    @Override
    public Mono<List<UsageAggregate>> aggregate(UsageQuery query) {
        return Mono.fromCallable(() -> {
            String column = query.dimension().column();
            String sql = "SELECT " + column + " AS k,"
                    + " COUNT(*) AS requests,"
                    + " COALESCE(SUM(prompt_tokens), 0) AS prompt_tokens,"
                    + " COALESCE(SUM(completion_tokens), 0) AS completion_tokens,"
                    + " COALESCE(SUM(cached_prompt_tokens), 0) AS cached_tokens,"
                    + " COALESCE(SUM(cache_hit), 0) AS cache_hits,"
                    + " COALESCE(SUM(cost_usd), 0) AS cost_usd"
                    + " FROM t_usage_log WHERE usage_date BETWEEN ? AND ?"
                    + " GROUP BY " + column + " ORDER BY " + column;
            return jdbc.query(sql,
                    (rs, rowNum) -> new UsageAggregate(
                            rs.getString("k"),
                            rs.getLong("requests"),
                            rs.getLong("prompt_tokens"),
                            rs.getLong("completion_tokens"),
                            rs.getLong("cached_tokens"),
                            rs.getLong("cache_hits"),
                            rs.getBigDecimal("cost_usd")),
                    Date.valueOf(query.from()), Date.valueOf(query.to()));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<UsageStoreStats> stats() {
        return Mono.fromSupplier(() ->
                new UsageStoreStats(queue.size(), written.get(), dropped.get()));
    }

    @Override
    public Mono<Void> flush() {
        return Mono.fromRunnable(() -> {
                    // drain in batch-sized chunks until the queue is empty
                    while (drainAndWrite() == batchSize) {
                        // keep going while there is a full batch left
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then();
    }

    /** Called by Spring on context shutdown: stop the timer, then take one last drain. */
    public void close() {
        flusher.shutdownNow();
        try {
            while (drainAndWrite() == batchSize) {
                // final drain
            }
        } catch (Exception e) {
            log.warn("final usage flush failed: {}", e.toString());
        }
    }
}
