package io.modelgate.cache;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * In-process cache of embedding vectors keyed by the prompt's SHA-256.
 *
 * <p>Every request needs an embedding just to *look* in the semantic cache, so without this
 * the gateway would pay an embedding round trip per request even on hits. Identical prompts
 * (the retry-heavy, bot-heavy traffic a gateway actually sees) then cost nothing extra.
 */
public final class EmbeddingCache {

    private record Entry(double[] vector, long createdAtMillis) {
    }

    private final Map<String, Entry> entries;
    private final long ttlMillis;

    public EmbeddingCache(int maxEntries, long ttlSeconds) {
        this.ttlMillis = ttlSeconds * 1000L;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
                return size() > maxEntries;
            }
        };
    }

    public Optional<double[]> get(String text) {
        String key = hash(text);
        synchronized (entries) {
            Entry entry = entries.get(key);
            if (entry == null) {
                return Optional.empty();
            }
            if (System.currentTimeMillis() - entry.createdAtMillis() > ttlMillis) {
                entries.remove(key);
                return Optional.empty();
            }
            return Optional.of(entry.vector());
        }
    }

    public void put(String text, double[] vector) {
        String key = hash(text);
        synchronized (entries) {
            entries.put(key, new Entry(vector, System.currentTimeMillis()));
        }
    }

    public void clear() {
        synchronized (entries) {
            entries.clear();
        }
    }

    public static String hash(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
