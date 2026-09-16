package io.modelgate.usage;

/** Aggregation axis of a usage report. */
public enum UsageDimension {
    DATE("usage_date"),
    TENANT("tenant_id"),
    MODEL("model_group"),
    KEY("key_id"),
    /** routing arm — the axis that turns the ledger into an A/B result */
    ARM("arm");

    private final String column;

    UsageDimension(String column) {
        this.column = column;
    }

    /** Physical column name — never user input, so it is safe to interpolate into SQL. */
    public String column() {
        return column;
    }
}
