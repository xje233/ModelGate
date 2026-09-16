package io.modelgate.cache;

/**
 * Result of one semantic cache probe. The payload stays opaque here: this module only
 * deals with vectors and storage, the gateway layer owns serialization and policy.
 */
public record CacheLookup(boolean hit, double similarity, String entryId, String payload) {

    private static final CacheLookup MISS = new CacheLookup(false, 0, null, null);

    public static CacheLookup miss() {
        return MISS;
    }

    public static CacheLookup hit(double similarity, String entryId, String payload) {
        return new CacheLookup(true, similarity, entryId, payload);
    }
}
