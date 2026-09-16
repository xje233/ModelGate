package io.modelgate.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import io.modelgate.testkit.RedisTestServer;

/** Runs the Redis-backed cache against a real Redis (skipped when no binary is available). */
class RedisSemanticCacheTest {

    private static RedisTestServer server;
    private static LettuceConnectionFactory connectionFactory;
    private static ReactiveStringRedisTemplate redis;
    private static RedisSemanticCache cache;

    @BeforeAll
    static void startRedis() throws IOException {
        Optional<RedisTestServer> started = RedisTestServer.tryStart();
        assumeTrue(started.isPresent(), RedisTestServer.hint());
        server = started.get();
        connectionFactory = new LettuceConnectionFactory("127.0.0.1", server.port());
        connectionFactory.afterPropertiesSet();
        redis = new ReactiveStringRedisTemplate(connectionFactory);
        cache = new RedisSemanticCache(redis, 100, 60);
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

    @BeforeEach
    void reset() {
        cache.clear().block();
    }

    @Test
    void hitReturnsPayloadAndCreatesTheExpectedKeys() {
        cache.put("sc:key-a:fast-medium:x", "e1", new double[]{0.1, 0.2, 0.3}, "payload-1").block();

        CacheLookup lookup = cache.find("sc:key-a:fast-medium:x", new double[]{0.1, 0.2, 0.3}, 0.95)
                .block();
        assertTrue(lookup.hit());
        assertEquals("payload-1", lookup.payload());
        assertEquals(1.0, lookup.similarity(), 1e-6);
        assertEquals(1L, cache.size("sc:key-a:fast-medium:x").block());

        // the three-key layout really exists: vector hash, payload hash, index zset
        assertTrue(Boolean.TRUE.equals(redis.hasKey("sc:key-a:fast-medium:x:vec").block()));
        assertTrue(Boolean.TRUE.equals(redis.hasKey("sc:key-a:fast-medium:x:data").block()));
        assertTrue(Boolean.TRUE.equals(redis.hasKey("sc:key-a:fast-medium:x:idx").block()));
    }

    @Test
    void differentPromptMisses() {
        cache.put("ns", "e1", new double[]{1, 0, 0}, "payload").block();
        assertFalse(cache.find("ns", new double[]{0, 0, 1}, 0.95).block().hit());
    }

    @Test
    void namespacesAreIsolated() {
        cache.put("sc:key-a:fast-medium:x", "e1", new double[]{1, 1, 1}, "A's answer").block();
        assertFalse(cache.find("sc:key-b:fast-medium:x", new double[]{1, 1, 1}, 0.95).block().hit());
    }

    @Test
    void entryCountIsBoundedAndOldestIsTrimmed() {
        RedisSemanticCache bounded = new RedisSemanticCache(redis, 2, 60);
        bounded.put("bounded", "e1", new double[]{1, 0, 0}, "one").block();
        bounded.put("bounded", "e2", new double[]{0, 1, 0}, "two").block();
        bounded.put("bounded", "e3", new double[]{0, 0, 1}, "three").block();

        assertEquals(2L, bounded.size("bounded").block());
        assertFalse(bounded.find("bounded", new double[]{1, 0, 0}, 0.95).block().hit());
        assertTrue(bounded.find("bounded", new double[]{0, 0, 1}, 0.95).block().hit());
    }

    @Test
    void clearRemovesAllNamespaces() {
        cache.put("sc:key-a:fast-medium:x", "e1", new double[]{1, 0}, "payload").block();
        cache.clear().block();
        assertEquals(0L, cache.size("sc:key-a:fast-medium:x").block());
    }
}
