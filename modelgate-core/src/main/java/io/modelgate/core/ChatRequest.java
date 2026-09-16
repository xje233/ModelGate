package io.modelgate.core;

import java.util.List;

/**
 * Chat completion request, OpenAI canonical format. This is the single data contract of the
 * gateway: the public API accepts it, every provider transforms it into its own wire format,
 * and every response is normalized back into the core types.
 *
 * <p>Fields map to snake_case on the wire ({@code max_tokens}, {@code top_p}, ...).
 */
public record ChatRequest(
        String model,
        List<Message> messages,
        Boolean stream,
        Double temperature,
        Integer maxTokens,
        Double topP,
        List<String> stop) {

    public ChatRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
    }

    /** The router overrides the public alias with the real upstream model id. */
    public ChatRequest withModel(String newModel) {
        return new ChatRequest(newModel, messages, stream, temperature, maxTokens, topP, stop);
    }

    public String lastUserContent() {
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if ("user".equals(m.role()) && m.content() != null) {
                return m.content();
            }
        }
        return "";
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String model;
        private List<Message> messages = List.of();
        private Boolean stream;
        private Double temperature;
        private Integer maxTokens;
        private Double topP;
        private List<String> stop;

        public Builder model(String model) {
            this.model = model;
            return this;
        }

        public Builder messages(List<Message> messages) {
            this.messages = messages;
            return this;
        }

        public Builder stream(boolean stream) {
            this.stream = stream;
            return this;
        }

        public Builder temperature(Double temperature) {
            this.temperature = temperature;
            return this;
        }

        public Builder maxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
            return this;
        }

        public Builder topP(Double topP) {
            this.topP = topP;
            return this;
        }

        public Builder stop(List<String> stop) {
            this.stop = stop;
            return this;
        }

        public ChatRequest build() {
            return new ChatRequest(model, messages, stream, temperature, maxTokens, topP, stop);
        }
    }
}
