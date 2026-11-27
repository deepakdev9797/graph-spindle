package io.graphspindle.llm;

/**
 * A deliberately pessimistic prompt-size estimate (ADR-031, ADR-034). Measured on gemma4 (Ollama
 * {@code prompt_eval_count}), digits, hex, base64, punctuation, emoji and CJK tokenize 2-4x worse than
 * prose, so: one token per digit and per ASCII punctuation character, one per two other ASCII
 * characters (letters, whitespace), two per non-ASCII BMP code point and four per supplementary-plane
 * code point (emoji). Used to refuse a call that would not fit the model context instead of letting
 * the provider truncate it silently (Ollama drops the head of an oversize prompt, i.e. the rules).
 */
public final class TokenEstimator {

    private TokenEstimator() {
    }

    public static long estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long single = 0;
        long pairs = 0;
        long wide = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 128) {
                if ((cp >= '0' && cp <= '9') || isAsciiPunctuation(cp)) {
                    single++;
                } else {
                    pairs++;
                }
            } else {
                wide += Character.isSupplementaryCodePoint(cp) ? 4 : 2;
            }
        }
        return single + (pairs + 1) / 2 + wide;
    }

    private static boolean isAsciiPunctuation(int cp) {
        return (cp >= 33 && cp <= 47) || (cp >= 58 && cp <= 64) || (cp >= 91 && cp <= 96) || (cp >= 123 && cp <= 126);
    }

    /** Estimated prompt tokens of a request: both messages and the schema (sent as format or in the prompt). */
    public static long estimate(LlmRequest request) {
        return estimate(request.system()) + estimate(request.user()) + estimate(request.jsonSchema()) + 16;
    }
}
