package io.modelgate.quota;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import reactor.core.publisher.Mono;

/**
 * Cluster-safe limiter. All three dimensions are checked inside ONE Lua script, so a request
 * costs a single round trip instead of three, and the check-then-consume sequence is atomic
 * on the Redis side (no way for two replicas to both pass the last slot).
 */
public final class RedisQuotaLimiter implements QuotaLimiter {

    private static final RedisScript<List> ACQUIRE = RedisScript.of(
            new ClassPathResource("lua/quota_acquire.lua"), List.class);
    private static final RedisScript<Long> CHARGE = RedisScript.of(
            new ClassPathResource("lua/quota_charge.lua"), Long.class);

    private final ReactiveStringRedisTemplate redis;
    private final long windowMillis;

    public RedisQuotaLimiter(ReactiveStringRedisTemplate redis) {
        this(redis, Duration.ofMinutes(1));
    }

    public RedisQuotaLimiter(ReactiveStringRedisTemplate redis, Duration window) {
        this.redis = redis;
        this.windowMillis = window.toMillis();
    }

    @Override
    public Mono<QuotaDecision> acquire(QuotaRequest request, String requestId) {
        List<String> keys = new ArrayList<>(6);
        for (QuotaScope scope : request.scopes()) {
            keys.add(request.rpmKeyOf(scope));
            keys.add(request.tpmKeyOf(scope));
        }
        // RedisStringTemplate args must be strings; the Lua script converts with tonumber()
        List<Object> args = new ArrayList<>(9);
        args.add(String.valueOf(System.currentTimeMillis()));
        args.add(String.valueOf(windowMillis));
        for (QuotaScope scope : request.scopes()) {
            QuotaLimits limits = request.limitsOf(scope);
            args.add(String.valueOf(limits.rpm()));
            args.add(String.valueOf(limits.tpm()));
        }
        args.add(requestId);

        return redis.execute(ACQUIRE, keys, args)
                .next()
                .map(result -> toDecision(result, request));
    }

    @Override
    public Mono<Void> chargeTokens(QuotaRequest request, int promptTokens, int completionTokens) {
        int tokens = Math.max(0, promptTokens) + Math.max(0, completionTokens);
        if (tokens == 0 || request.scopes().stream().allMatch(s -> request.limitsOf(s).tpmUnlimited())) {
            return Mono.empty();
        }
        List<String> keys = new ArrayList<>(3);
        for (QuotaScope scope : request.scopes()) {
            keys.add(request.tpmKeyOf(scope));
        }
        List<Object> args = new ArrayList<>(5);
        args.add(String.valueOf(tokens));
        args.add(String.valueOf(windowMillis));
        for (QuotaScope scope : request.scopes()) {
            args.add(String.valueOf(request.limitsOf(scope).tpm()));
        }
        return redis.execute(CHARGE, keys, args).then();
    }

    private QuotaDecision toDecision(List<?> result, QuotaRequest request) {
        long allowed = number(result.get(0));
        if (allowed == 1L) {
            return QuotaDecision.allow(number(result.get(3)));
        }
        int dimension = (int) number(result.get(1));           // 1..3
        long retryAfterMillis = number(result.get(2));
        List<QuotaScope> scopes = request.scopes();
        QuotaScope scope = scopes.get(Math.max(0, Math.min(scopes.size() - 1, dimension - 1)));
        return QuotaDecision.reject(scope, request.subjectOf(scope), retryAfterMillis);
    }

    private static long number(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }
}
