package io.modelgate.router;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelgate.core.Deployment;

/**
 * Routing decisions, deliberately separated from HTTP concerns so the module stays
 * unit-testable and embeddable.
 *
 * <p>Three distinct mechanisms (do not conflate them):
 * <ul>
 *   <li><b>candidates</b> — in-group ordering (weighted random, deployments whose breaker is
 *       OPEN are skipped; a HALF_OPEN one is offered as the recovery probe)</li>
 *   <li><b>chain</b> — candidates plus fallback groups appended in configured order.
 *       Walking the chain implements retry (in-group, next deployment) and
 *       fallback (cross-group, different provider/model) in one pass.</li>
 *   <li><b>circuit state</b> — per-deployment CLOSED/OPEN/HALF_OPEN, owned by {@link RouterState}</li>
 * </ul>
 */
public final class RouterService {

    private static final Logger log = LoggerFactory.getLogger(RouterService.class);

    private final Map<String, List<Deployment>> groups;
    private final Map<String, Deployment> byName;
    private final Map<String, List<String>> fallbacks;
    private final Map<String, GroupPolicy> policies;
    private final RouterState state;

    public RouterService(List<Deployment> deployments,
                         Map<String, List<String>> fallbacks,
                         RouterState state) {
        this(deployments, fallbacks, Map.of(), state);
    }

    public RouterService(List<Deployment> deployments,
                         Map<String, List<String>> fallbacks,
                         Map<String, GroupPolicy> policies,
                         RouterState state) {
        this.groups = deployments.stream()
                .collect(Collectors.groupingBy(
                        Deployment::group, LinkedHashMap::new, Collectors.toList()));
        this.byName = deployments.stream()
                .collect(Collectors.toMap(Deployment::name, d -> d, (a, b) -> a, LinkedHashMap::new));
        this.fallbacks = Map.copyOf(fallbacks == null ? Map.of() : fallbacks);
        this.policies = Map.copyOf(policies == null ? Map.of() : policies);
        this.state = state;
        log.info("router initialized: groups={} fallbacks={} canary={}",
                groups.keySet(), this.fallbacks, canaryGroups());
    }

    private List<String> canaryGroups() {
        return policies.entrySet().stream()
                .filter(e -> e.getValue().hasCanary())
                .map(e -> e.getKey() + "->" + e.getValue().canaryDeployment()
                        + "(" + e.getValue().canaryPercentage() + "%)")
                .toList();
    }

    /** Lookup used by non-routing callers (e.g. the embedding deployment of the cache). */
    public Deployment deploymentByName(String name) {
        return byName.get(name);
    }

    public Collection<Deployment> deployments() {
        return byName.values();
    }

    public Set<String> groups() {
        return groups.keySet();
    }

    public boolean hasGroup(String group) {
        return groups.containsKey(group);
    }

    /** Weighted-random ordered, cooldown-skipping list of the group's deployments. */
    public List<Deployment> candidates(String group) {
        return candidates(group, null);
    }

    /**
     * Ordering of the group's deployments for one caller.
     *
     * @param stickinessKey caller identity (API key id). For a canary group it decides the arm
     *                      deterministically, so the same caller never flips between arms —
     *                      which is what makes a canary experiment readable.
     */
    public List<Deployment> candidates(String group, String stickinessKey) {
        List<Deployment> list = groups.get(group);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        List<Deployment> available = list.stream()
                .filter(d -> state.isAvailable(d.key()))
                .collect(Collectors.toCollection(ArrayList::new));
        if (available.isEmpty()) {
            return List.of();
        }
        GroupPolicy policy = policies.getOrDefault(group, GroupPolicy.plain());
        if (!policy.hasCanary()) {
            return weightedOrder(available);
        }
        Deployment canary = available.stream()
                .filter(d -> d.name().equals(policy.canaryDeployment()))
                .findFirst()
                .orElse(null);
        if (canary == null) {
            // canary deployment is not in the pool (or is in cooldown): degrade to the stable pool
            return weightedOrder(available);
        }
        List<Deployment> ordered = new ArrayList<>(available.size());
        if (inCanaryArm(stickinessKey, policy.canaryPercentage())) {
            // canary first, the rest of the group stays behind it as the in-group retry path
            ordered.add(canary);
            available.stream().filter(d -> !d.equals(canary)).forEach(ordered::add);
            return ordered;
        }
        List<Deployment> stable = available.stream().filter(d -> !d.equals(canary)).toList();
        return weightedOrder(stable.isEmpty() ? available : stable);
    }

    /** Which arm a caller lands in: {@code canary}, {@code stable}, or {@code single}. */
    public String armOf(String group, String stickinessKey) {
        if (!groups.containsKey(group)) {
            return "unknown";
        }
        GroupPolicy policy = policies.getOrDefault(group, GroupPolicy.plain());
        if (!policy.hasCanary()) {
            return "single";
        }
        return inCanaryArm(stickinessKey, policy.canaryPercentage()) ? "canary" : "stable";
    }

    /**
     * Deterministic bucketing on the caller identity: same caller, same arm, forever.
     * {@link String#hashCode()} is specified by the JDK, so the bucket is stable across JVMs
     * and replicas — a caller does not switch arms by hitting a different gateway instance.
     */
    private static boolean inCanaryArm(String stickinessKey, int percentage) {
        if (percentage <= 0) {
            return false;
        }
        if (percentage >= 100) {
            return true;
        }
        return bucketOf(stickinessKey == null ? "" : stickinessKey) < percentage;
    }

    /**
     * Bucket in [0, 100) after an avalanche mix.
     *
     * <p>The mix is not decoration. {@code String.hashCode()} of sequentially issued caller ids
     * differs by exactly 1 ({@code key-a}=…411, {@code key-b}=…412), so bucketing the raw hash
     * puts consecutive callers in consecutive buckets — a 30% canary then becomes "the first N
     * letters of your naming scheme", which is neither a random sample nor a bounded blast
     * radius. Running the hash through the MurmurHash3 32-bit finalizer decorrelates neighbours
     * while staying deterministic across JVMs and replicas.
     */
    private static int bucketOf(String key) {
        int hash = key.hashCode();
        hash ^= hash >>> 16;
        hash *= 0x85ebca6b;
        hash ^= hash >>> 13;
        hash *= 0xc2b2ae35;
        hash ^= hash >>> 16;
        return Math.floorMod(hash, 100);
    }

    /** Full attempt chain: own group first, then fallback groups in configured order. */
    public List<Deployment> chain(String group) {
        return chain(group, null);
    }

    public List<Deployment> chain(String group, String stickinessKey) {
        List<Deployment> result = new ArrayList<>(candidates(group, stickinessKey));
        for (String fallbackGroup : fallbacks.getOrDefault(group, List.of())) {
            result.addAll(candidates(fallbackGroup, stickinessKey));
        }
        return result;
    }

    public void recordSuccess(Deployment deployment) {
        state.recordSuccess(deployment.key());
    }

    public void recordFailure(Deployment deployment) {
        state.recordFailure(deployment.key());
    }

    /**
     * Weighted sampling without replacement: every candidate appears exactly once.
     *
     * <p>Takes an <b>already filtered</b> pool on purpose: {@link RouterState#isAvailable} has a
     * side effect (it hands out the single HALF_OPEN probe permit), so it must be called exactly
     * once per deployment per routing decision — calling it again here would consume the permit
     * and silently drop a recovering deployment out of rotation.
     */
    private List<Deployment> weightedOrder(List<Deployment> available) {
        List<Deployment> pool = new ArrayList<>(available);
        List<Deployment> ordered = new ArrayList<>(pool.size());
        while (!pool.isEmpty()) {
            int total = 0;
            for (Deployment d : pool) {
                total += d.weight();
            }
            int r = ThreadLocalRandom.current().nextInt(total);
            int acc = 0;
            int picked = pool.size() - 1;
            for (int i = 0; i < pool.size(); i++) {
                acc += pool.get(i).weight();
                if (r < acc) {
                    picked = i;
                    break;
                }
            }
            ordered.add(pool.remove(picked));
        }
        return ordered;
    }
}
