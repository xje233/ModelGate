package io.modelgate.quota;

/**
 * The three quota dimensions. Declaration order is the enforcement order:
 * the most specific dimension is checked first, so a single abusive API key is
 * rejected before it can eat the whole tenant's budget.
 */
public enum QuotaScope {
    KEY,
    TENANT,
    MODEL
}
