package io.modelgate.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import io.modelgate.core.PromptTokensDetails;
import io.modelgate.core.Usage;

/**
 * Cost math, including the part that is easy to get wrong: prompt caching
 * changes the input
 * side of the bill, and different vendors change it in opposite directions.
 */
class CostCalculatorTest {

  /** gpt-4o: $2.50 / 1M input, $10.00 / 1M output */
  private static final String MODEL = "gpt-4o";

  @Test
  void plainUsageIsTokensTimesPrice() {
    BigDecimal cost = CostCalculator.costUsd(MODEL, Usage.of(1_000_000, 1_000_000));
    assertEquals(0, new BigDecimal("12.500000").compareTo(cost));
  }

  @Test
  void cachedInputIsBilledAtTheDiscount() {
    // 1M prompt of which 1M cached at the OpenAI 0.5x hit multiplier = $1.25
    BigDecimal cost = CostCalculator.costUsd(MODEL,
        Usage.of(1_000_000, 0, new PromptTokensDetails(1_000_000, 0)),
        CostCalculator.CachePricing.OPENAI);
    assertEquals(0, new BigDecimal("1.250000").compareTo(cost));
  }

  @Test
  void cacheWritesCostMoreThanFreshInput() {
    BigDecimal fresh = CostCalculator.costUsd(MODEL, Usage.of(1_000_000, 0),
        CostCalculator.CachePricing.OPENAI);
    BigDecimal written = CostCalculator.costUsd(MODEL,
        Usage.of(1_000_000, 0, new PromptTokensDetails(0, 1_000_000)),
        CostCalculator.CachePricing.OPENAI);
    assertEquals(0, fresh.multiply(BigDecimal.valueOf(2)).compareTo(written));
  }

  @Test
  void anthropicBillsHitsAndWritesAboveListPrice() {
    BigDecimal cached = CostCalculator.costUsd(MODEL,
        Usage.of(1_000_000, 0, new PromptTokensDetails(1_000_000, 0)),
        CostCalculator.CachePricing.ANTHROPIC);
    assertEquals(0, new BigDecimal("3.125000").compareTo(cached));
  }

  @Test
  void freshAndCachedSplitIsAdditive() {
    // 1000 tokens total, 400 cached: 600 fresh at 1x + 400 at 0.5x on a $1/1M model
    BigDecimal cost = CostCalculator.costUsd("qwen-max",
        Usage.of(1_000, 0, new PromptTokensDetails(400, 0)),
        CostCalculator.CachePricing.OPENAI);
    double expected = (600 * 1.60 + 400 * 1.60 * 0.5) / 1_000_000.0;
    assertEquals(0, BigDecimal.valueOf(expected).setScale(6, java.math.RoundingMode.HALF_UP)
        .compareTo(cost));
  }

  @Test
  void cacheMultipliersArePickedByProvider() {
    assertEquals(0.5, CostCalculator.CachePricing.forProvider("openai").hitMultiplier());
    assertEquals(1.25, CostCalculator.CachePricing.forProvider("anthropic").hitMultiplier());
    assertEquals(1.25, CostCalculator.CachePricing.forProvider("claude").writeMultiplier());
    assertEquals(2.0, CostCalculator.CachePricing.forProvider("deepseek").writeMultiplier());
    assertEquals(2.0, CostCalculator.CachePricing.forProvider(null).writeMultiplier());
  }

  @Test
  void unknownModelCostsNothingInsteadOfGuessing() {
    assertEquals(0, BigDecimal.ZERO.compareTo(
        CostCalculator.costUsd("no-such-model", Usage.of(1000, 1000))));
    assertEquals(0, BigDecimal.ZERO.compareTo(CostCalculator.costUsd(MODEL, null)));
  }

  @Test
  void cachedTokensCannotExceedThePrompt() {
    // a provider reporting garbage must not produce a negative or absurd bill:
    // cached is clamped to the prompt (100) and the write side gets what is left
    // (0),
    // so the whole 100-token prompt is billed as a cache hit at 0.5x
    BigDecimal cost = CostCalculator.costUsd(MODEL,
        Usage.of(100, 0, new PromptTokensDetails(10_000, 10_000)),
        CostCalculator.CachePricing.OPENAI);
    assertTrue(cost.signum() >= 0);
    assertEquals(0, new BigDecimal("0.000125").compareTo(cost));
  }
}
