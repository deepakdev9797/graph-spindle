package io.graphspindle.llm;

/**
 * A deliberately pessimistic prompt-size estimate (ADR-031): one token per three ASCII characters
 * (JSON, ids and code tokenize worse than prose) and one token per non-ASCII code point. Used to
 * refuse a call that would not fit the model context instead of letting the provider truncate it
 * silently (Ollama drops the head of an oversize prompt, i.e. the system rules).
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static long estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long ascii = 0;
        long other = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 128) {
                ascii++;
            } else {
                other++;
            }
        }
        return (ascii + 2) / 3 + other;
    }

    /** Estimated prompt tokens of a request (both messages and the schema, if it is sent separately). */
    public static long estimate(LlmRequest request) {
        return estimate(request.system()) + estimate(request.user()) + 16;
    }
}
