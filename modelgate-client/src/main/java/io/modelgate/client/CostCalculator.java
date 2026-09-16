package io.modelgate.client;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import io.modelgate.core.Usage;

/**
 * Price-table based cost calculation (USD per 1M tokens).
 *
 * <p>Prompt caching makes a naive "tokens × price" wrong, so the input side is split three ways:
 * fresh tokens at list price, <b>cache hits</b> at a discount, <b>cache writes</b> at a premium.
 * The multipliers differ per vendor — OpenAI bills a hit at 0.5× and a write at 2×, Anthropic
 * bills both at 1.25× — which is exactly why cost accounting belongs in the gateway rather than
 * in each caller.
 */
public final class CostCalculator {

    /** input price, output price — USD per 1M tokens */
    private record Price(double input, double output) {
    }

    /** multipliers applied to the input price for cache hits / cache writes */
    public record CachePricing(double hitMultiplier, double writeMultiplier) {

        public static final CachePricing NONE = new CachePricing(1.0, 1.0);
        public static final CachePricing OPENAI = new CachePricing(0.5, 2.0);
        public static final CachePricing ANTHROPIC = new CachePricing(1.25, 1.25);
        public static final CachePricing DEFAULT = OPENAI;

        public static CachePricing forProvider(String provider) {
            if (provider == null) {
                return DEFAULT;
            }
            return switch (provider.toLowerCase()) {
                case "anthropic", "claude" -> ANTHROPIC;
                default -> DEFAULT;
            };
        }
    }

    private static final Map<String, Price> PRICES = Map.ofEntries(
            Map.entry("gpt-4o", new Price(2.50, 10.00)),
            Map.entry("gpt-4o-mini", new Price(0.15, 0.60)),
            Map.entry("gpt-4.1", new Price(2.00, 8.00)),
            Map.entry("gpt-4.1-mini", new Price(0.40, 1.60)),
            Map.entry("deepseek-chat", new Price(0.27, 1.10)),
            Map.entry("deepseek-reasoner", new Price(0.55, 2.19)),
            Map.entry("moonshot-v1-8k", new Price(0.14, 0.14)),
            Map.entry("qwen-max", new Price(1.60, 6.40)),
            Map.entry("qwen-plus", new Price(0.40, 1.20)),
            Map.entry("qwen-turbo", new Price(0.05, 0.20)),
            // mock upstreams: priced so cost and cache-savings numbers stay meaningful offline
            Map.entry("mock-fast-a", new Price(0.15, 0.60)),
            Map.entry("mock-fast-b", new Price(0.15, 0.60)),
            Map.entry("mock-smart", new Price(2.50, 10.00)),
            Map.entry("mock-broken", new Price(0.15, 0.60)),
            Map.entry("mock-embed", new Price(0.02, 0.00)));

    private CostCalculator() {
    }

    public static BigDecimal costUsd(String model, Usage usage) {
        return costUsd(model, usage, CachePricing.DEFAULT);
    }

    public static BigDecimal costUsd(String model, Usage usage, CachePricing pricing) {
        if (usage == null || model == null) {
            return BigDecimal.ZERO;
        }
        Price price = PRICES.get(model);
        if (price == null) {
            return BigDecimal.ZERO;
        }
        int prompt = usage.prompt();
        int cached = Math.min(usage.cachedPrompt(), prompt);
        int cacheWrite = Math.min(usage.cacheWritePrompt(), Math.max(0, prompt - cached));
        int fresh = Math.max(0, prompt - cached - cacheWrite);

        double inputCost = fresh * price.input()
                + cached * price.input() * pricing.hitMultiplier()
                + cacheWrite * price.input() * pricing.writeMultiplier();
        double outputCost = usage.completion() * price.output();

        return BigDecimal.valueOf((inputCost + outputCost) / 1_000_000.0)
                .setScale(6, RoundingMode.HALF_UP);
    }

    /** Tokens that would have been billed if the answer had been produced upstream. */
    public static int totalTokens(Usage usage) {
        return usage == null ? 0 : usage.prompt() + usage.completion();
    }
}
