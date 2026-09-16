package io.modelgate.client;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

import io.modelgate.core.Usage;

/**
 * Price-table based cost calculation (USD per 1M tokens). The bundled table mirrors
 * LiteLLM's public price sheet; unknown models price at zero and get logged instead.
 */
public final class CostCalculator {

    /** input price, output price — USD per 1M tokens */
    private record Price(double input, double output) {
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
        if (usage == null || model == null) {
            return BigDecimal.ZERO;
        }
        Price price = PRICES.get(model);
        if (price == null) {
            return BigDecimal.ZERO;
        }
        double in = usage.promptTokens() == null ? 0 : usage.promptTokens();
        double out = usage.completionTokens() == null ? 0 : usage.completionTokens();
        return BigDecimal.valueOf(in / 1_000_000.0 * price.input()
                        + out / 1_000_000.0 * price.output())
                .setScale(6, RoundingMode.HALF_UP);
    }
}
