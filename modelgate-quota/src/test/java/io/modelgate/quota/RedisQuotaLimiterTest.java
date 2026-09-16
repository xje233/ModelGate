package io.modelgate.quota;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import io.modelgate.testkit.RedisTestServer;

/**
 * Runs the real Lua scripts against a real Redis. This is the test that proves the
 * single-round-trip atomic path works, not just the in-process fallback.
 */
class RedisQuotaLimiterTest {

    private static RedisTestServer server;
    private static LettuceConnectionFactory connectionFactory;
    private static ReactiveStringRedisTemplate redis;
    private static RedisQuotaLimiter limiter;

    @BeforeAll
    static void startRedis() throws IOException {
        Optional<RedisTestServer> started = RedisTestServer.tryStart();
        assumeTrue(started.isPresent(), RedisTestServer.hint());
        server = started.get();
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", server.port());
        connectionFactory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connectionFactory);
        redis.execute(conn -> conn.serverCommands().flushAll()).blockLast();
        limiter = new RedisQuotaLimiter(redis, Duration.ofSeconds(60));
    }

    @AfterAll
    static void stopRedis() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        if (server != null) {
            server.close();
        }
    }

    private static QuotaRequest request(String keyId, QuotaLimits key, QuotaLimits tenant,
                                        QuotaLimits model) {
        return new QuotaRequest(keyId, "tenant-a", "fast-medium", key, tenant, model);
    }

    private static final QuotaLimits UNLIMITED = QuotaLimits.UNLIMITED;

    @Test
    void rpmLimitIsEnforcedAtomically() {
        QuotaRequest request = request("key-rpm", QuotaLimits.of(2, -1), UNLIMITED, UNLIMITED);

        assertTrue(limiter.acquire(request, "r1").block().allowed());
        assertTrue(limiter.acquire(request, "r2").block().allowed());

        QuotaDecision third = limiter.acquire(request, "r3").block();
        assertFalse(third.allowed());
        assertEquals(QuotaScope.KEY, third.rejectedScope());
        assertEquals("key-rpm", third.rejectedSubject());
        assertTrue(third.retryAfterSeconds() >= 1);

        // the sliding-window zset really exists in Redis with exactly the consumed members
        Long size = redis.opsForZSet().size(QuotaKeys.rpm(QuotaScope.KEY, "key-rpm")).block();
        assertEquals(2L, size);
    }

    @Test
    void tenantDimensionIsReportedWhenKeyHasRoom() {
        QuotaRequest request = request("key-tenant", QuotaLimits.of(100, -1),
                QuotaLimits.of(1, -1), UNLIMITED);

        assertTrue(limiter.acquire(request, "r1").block().allowed());
        QuotaDecision second = limiter.acquire(request, "r2").block();
        assertFalse(second.allowed());
        assertEquals(QuotaScope.TENANT, second.rejectedScope());
        assertEquals("tenant-a", second.rejectedSubject());
    }

    @Test
    void chargedTokensCountTowardsTpm() {
        QuotaRequest request = request("key-tpm", QuotaLimits.of(-1, 100), UNLIMITED, UNLIMITED);

        assertTrue(limiter.acquire(request, "r1").block().allowed());
        limiter.chargeTokens(request, 30, 30).block();
        assertTrue(limiter.acquire(request, "r2").block().allowed());

        limiter.chargeTokens(request, 30, 30).block();
        QuotaDecision third = limiter.acquire(request, "r3").block();
        assertFalse(third.allowed());
        assertEquals(QuotaScope.KEY, third.rejectedScope());

        String counter = redis.opsForValue()
                .get(QuotaKeys.tpm(QuotaScope.KEY, "key-tpm")).block();
        assertNotNull(counter);
        assertEquals(120, Integer.parseInt(counter));
    }

    @Test
    void reportsRemainingRequestsToCaller() {
        QuotaRequest request = request("key-pass", QuotaLimits.of(5, -1), UNLIMITED, UNLIMITED);

        QuotaDecision first = limiter.acquire(request, "r1").block();
        assertTrue(first.allowed());
        assertEquals(4, first.remainingRequests());
    }

    @Test
    void modelDimensionIsEnforced() {
        QuotaRequest request = request("key-model", UNLIMITED, UNLIMITED, QuotaLimits.of(1, -1));

        assertTrue(limiter.acquire(request, "r1").block().allowed());
        QuotaDecision second = limiter.acquire(request, "r2").block();
        assertFalse(second.allowed());
        assertEquals(QuotaScope.MODEL, second.rejectedScope());
        assertEquals("fast-medium", second.rejectedSubject());
    }
}
