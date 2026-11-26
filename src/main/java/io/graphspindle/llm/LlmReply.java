package io.graphspindle.llm;

/** A completion: the raw text (untrusted) and what the provider reported about it. */
public record LlmReply(String text, String model, Integer promptTokens, Integer completionTokens, long millis) {

    public LlmReply {
        text = text == null ? "" : text;
    }
}
