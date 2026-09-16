package io.modelgate.usage;

import java.time.LocalDate;

/** One aggregation request: axis plus an inclusive date range. */
public record UsageQuery(UsageDimension dimension, LocalDate from, LocalDate to) {

    public static UsageQuery of(UsageDimension dimension, LocalDate from, LocalDate to) {
        return new UsageQuery(dimension, from, to);
    }

    public static UsageQuery today(UsageDimension dimension, LocalDate today) {
        return new UsageQuery(dimension, today, today);
    }
}
