package io.modelgate.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Runs the batched writer and the aggregation SQL against a real database (H2 in MySQL mode).
 * Production uses MySQL with the same statements — see ops/sql/schema.sql.
 */
class JdbcUsageStoreTest {

    private static final String SCHEMA = """
            CREATE TABLE t_usage_log (
                request_id            VARCHAR(64) NOT NULL,
                key_id                VARCHAR(64),
                tenant_id             VARCHAR(64),
                model_group           VARCHAR(64),
                model_id              VARCHAR(128),
                provider              VARCHAR(32),
                prompt_tokens         INT NOT NULL DEFAULT 0,
                completion_tokens     INT NOT NULL DEFAULT 0,
                cached_prompt_tokens  INT NOT NULL DEFAULT 0,
                cost_usd              DECIMAL(12,6) NOT NULL DEFAULT 0,
                cache_hit             INT NOT NULL DEFAULT 0,
                stream                INT NOT NULL DEFAULT 0,
                arm                   VARCHAR(16),
                attempted             VARCHAR(512),
                status                VARCHAR(32),
                usage_date            DATE NOT NULL,
                start_time            TIMESTAMP(3),
                completion_start_time TIMESTAMP(3),
                end_time              TIMESTAMP(3),
                PRIMARY KEY (request_id)
            )""";

    private JdbcTemplate jdbc;
    private JdbcUsageStore store;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:usage-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute(SCHEMA);
        // long flush interval: these tests drive the writer explicitly via flush()
        store = new JdbcUsageStore(dataSource, 100, 10, 60_000);
        today = LocalDate.of(2026, 9, 16);
    }

    private UsageRecord record(String id, String tenant, String model, int prompt, int completion,
                              boolean cacheHit, String cost) {
        return new UsageRecord(id, "key-" + tenant, tenant, model, model + "-upstream", "mock",
                prompt, completion, 0, new BigDecimal(cost), cacheHit, false, "single",
                "mock-a", "success", today, 1_000L, 1_050L, 1_500L);
    }

    @Test
    void batchesAreWrittenOnFlush() {
        for (int i = 0; i < 3; i++) {
            store.record(record("r" + i, "tenant-a", "fast-medium", 10, 20, false, "0.001"));
        }
        store.flush().block();

        Integer rows = jdbc.queryForObject("SELECT COUNT(*) FROM t_usage_log", Integer.class);
        assertEquals(3, rows);
        assertEquals(3, store.stats().block().written());
        assertEquals(0, store.stats().block().queued());

        // the receipt survives the round trip intact, including the TTFT anchor and the arm
        assertEquals(3, jdbc.queryForObject(
                "SELECT COUNT(*) FROM t_usage_log WHERE start_time IS NOT NULL "
                        + "AND completion_start_time IS NOT NULL AND arm = 'single'", Integer.class));
    }

    @Test
    void aggregationSqlGroupsByTenantModelAndDate() {
        store.record(record("r1", "tenant-a", "fast-medium", 10, 20, false, "0.001"));
        store.record(record("r2", "tenant-a", "fast-medium", 5, 5, true, "0.002"));
        store.record(record("r3", "tenant-b", "smart", 1, 1, false, "0.000"));
        store.flush().block();

        List<UsageAggregate> byTenant = store.aggregate(
                UsageQuery.of(UsageDimension.TENANT, today, today)).block();
        assertEquals(2, byTenant.size());
        UsageAggregate tenantA = byTenant.stream()
                .filter(a -> a.group().equals("tenant-a")).findFirst().orElseThrow();
        assertEquals(2, tenantA.requests());
        assertEquals(15, tenantA.promptTokens());
        assertEquals(25, tenantA.completionTokens());
        assertEquals(1, tenantA.cacheHits());
        assertEquals(0, new BigDecimal("0.003").compareTo(tenantA.costUsd()));

        assertEquals(2, store.aggregate(UsageQuery.of(UsageDimension.MODEL, today, today))
                .block().size());
        // keys are derived from the tenant in this fixture, so the two tenants give two keys
        assertEquals(2, store.aggregate(UsageQuery.of(UsageDimension.KEY, today, today))
                .block().size());
        assertEquals(1, store.aggregate(UsageQuery.of(UsageDimension.DATE, today, today))
                .block().size());
    }

    @Test
    void recordsOutsideTheRangeAreNotCounted() {
        store.record(record("r1", "tenant-a", "fast-medium", 10, 0, false, "0.001"));
        store.flush().block();

        List<UsageAggregate> yesterday = store.aggregate(
                UsageQuery.of(UsageDimension.TENANT, today.minusDays(1), today.minusDays(1))).block();
        assertTrue(yesterday.isEmpty());
    }

    @Test
    void aFullQueueDropsInsteadOfBlockingTheRequestPath() {
        JdbcUsageStore small = new JdbcUsageStore(jdbc.getDataSource(), 2, 1, 60_000);
        for (int i = 0; i < 5; i++) {
            small.record(record("q" + i, "tenant-a", "fast-medium", 1, 1, false, "0.001"));
        }
        UsageStoreStats stats = small.stats().block();
        assertEquals(2, stats.queued());
        assertEquals(3, stats.dropped(), "the request path must never block on accounting");
    }

    @Test
    void emptyQueueFlushIsHarmless() {
        store.flush().block();
        assertEquals(0L, store.stats().block().written());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM t_usage_log", Integer.class));
    }
}
