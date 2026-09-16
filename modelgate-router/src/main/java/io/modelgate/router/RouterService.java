package io.modelgate.router;

import java.util.ArrayList;
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
 *   <li><b>candidates</b> — in-group ordering (weighted random, cooling deployments skipped)</li>
 *   <li><b>chain</b> — candidates plus fallback groups appended in configured order.
 *       Walking the chain implements retry (in-group, next deployment) and
 *       fallback (cross-group, different provider/model) in one pass.</li>
 *   <li><b>cooldown</b> — per-deployment circuit state, owned by {@link RouterState}</li>
 * </ul>
 */
public final class RouterService {

    private static final Logger log = LoggerFactory.getLogger(RouterService.class);

    private final Map<String, List<Deployment>> groups;
    private final Map<String, List<String>> fallbacks;
    private final RouterState state;

    public RouterService(List<Deployment> deployments,
                         Map<String, List<String>> fallbacks,
                         RouterState state) {
        this.groups = deployments.stream()
                .collect(Collectors.groupingBy(
                        Deployment::group, LinkedHashMap::new, Collectors.toList()));
        this.fallbacks = Map.copyOf(fallbacks == null ? Map.of() : fallbacks);
        this.state = state;
        log.info("router initialized: groups={} fallbacks={}", groups.keySet(), this.fallbacks);
    }

    public Set<String> groups() {
        return groups.keySet();
    }

    public boolean hasGroup(String group) {
        return groups.containsKey(group);
    }

    /** Weighted-random ordered, cooldown-skipping list of the group's deployments. */
    public List<Deployment> candidates(String group) {
        List<Deployment> list = groups.get(group);
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return weightedOrder(list);
    }

    /** Full attempt chain: own group first, then fallback groups in configured order. */
    public List<Deployment> chain(String group) {
        List<Deployment> result = new ArrayList<>(candidates(group));
        for (String fallbackGroup : fallbacks.getOrDefault(group, List.of())) {
            result.addAll(candidates(fallbackGroup));
        }
        return result;
    }

    public void recordSuccess(Deployment deployment) {
        state.recordSuccess(deployment.key());
    }

    public void recordFailure(Deployment deployment) {
        state.recordFailure(deployment.key());
    }

    /** Weighted sampling without replacement: every candidate appears exactly once. */
    private List<Deployment> weightedOrder(List<Deployment> deployments) {
        List<Deployment> pool = deployments.stream()
                .filter(d -> !state.isCoolingDown(d.key()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
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
