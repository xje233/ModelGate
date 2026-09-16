package io.modelgate.cache;

import java.nio.ByteBuffer;
import java.util.Base64;

/**
 * Compact vector encoding for Redis (float32 + base64 ≈ 344 chars for 64 dims, versus ~1KB
 * for a JSON array of doubles). Precision loss is irrelevant for a similarity threshold.
 */
public final class VectorCodec {

    private VectorCodec() {
    }

    public static String encode(double[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES);
        for (double value : vector) {
            buffer.putFloat((float) value);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    public static double[] decode(String encoded) {
        byte[] bytes = Base64.getDecoder().decode(encoded);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        double[] vector = new double[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
