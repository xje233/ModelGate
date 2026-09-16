package io.modelgate.core;

/**
 * A single chat message, OpenAI canonical format.
 *
 * <p>Serialized as {@code {"role": "...", "content": "..."}} under the snake_case strategy.
 */
public record Message(String role, String content) {

    public static Message system(String content) {
        return new Message("system", content);
    }

    public static Message user(String content) {
        return new Message("user", content);
    }

    public static Message assistant(String content) {
        return new Message("assistant", content);
    }
}
