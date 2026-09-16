package io.modelgate.proxy.controller;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

import io.modelgate.usage.UsageAggregate;
import io.modelgate.usage.UsageDimension;
import io.modelgate.usage.UsageQuery;
import io.modelgate.usage.UsageStore;
import io.modelgate.usage.UsageStoreStats;

/**
 * Accounting read API. The same numbers a billing job or a dashboard would use.
 *
 * <p>Kept on a separate path ({@code /admin/**}) from the inference API on purpose: analytic
 * queries must not compete with completions for threads. In a real deployment these would sit
 * behind a separate listener and role — see plan.md pitfall #9, where a two-year aggregation
 * query on the same event loop took down a provider's health probes.
 */
@RestController
public class AdminController {

    private final UsageStore usageStore;

    public AdminController(UsageStore usageStore) {
        this.usageStore = usageStore;
    }

    @GetMapping("/admin/usage")
    public Mono<List<UsageAggregate>> usage(
            @RequestParam(defaultValue = "TENANT") UsageDimension dimension,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now().minusDays(7);
        LocalDate end = to != null ? to : LocalDate.now();
        return usageStore.aggregate(UsageQuery.of(dimension, start, end));
    }

    /** {@code dropped} is the number to alert on: requests served but never billed. */
    @GetMapping("/admin/usage/stats")
    public Mono<UsageStoreStats> stats() {
        return usageStore.stats();
    }

    @GetMapping("/admin/routing")
    public Map<String, Object> routing() {
        return Map.of(
                "note", "which model groups run a canary, and which deployment serves it",
                "see", "/v1/models for the public alias list");
    }
}
