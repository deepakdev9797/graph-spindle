package io.graphspindle.ingestion.pipeline;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The stage-1 result (ARCHITECTURE §3), read leniently: it is only a hint for candidate lookup and
 * for the reconcile prompt, never applied. First-person references use {@code P0} (no
 * {@code isSpeaker} mention, ADR-028).
 *
 * @param json the extraction re-serialized compactly (shown to the reconcile stage as data)
 * @param mentions named things, at most {@link #MAX_MENTIONS}
 * @param predicates predicates of the extracted statements
 */
public record Extraction(String json, List<Mention> mentions, Set<String> predicates) {

    static final int MAX_MENTIONS = 50;
    static final int MAX_ALIASES = 5;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record Mention(String name, String type, List<String> aliases) {
    }

    public static Extraction empty() {
        return new Extraction("{}", List.of(), Set.of());
    }

    /** The outermost JSON object with a {@code statements} array, or null when there is none. */
    public static Extraction parse(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        JsonNode n;
        try {
            n = JSON.readTree(raw.substring(start, end + 1));
        } catch (RuntimeException e) {
            return null;
        }
        if (!n.isObject() || !n.path("statements").isArray()) {
            return null;
        }
        List<Mention> mentions = new ArrayList<>();
        for (JsonNode m : n.path("mentions")) {
            if (mentions.size() >= MAX_MENTIONS) {
                break;
            }
            String name = text(m.get("name"));
            if (name == null || name.isBlank() || name.length() > 200) {
                continue;
            }
            List<String> aliases = new ArrayList<>();
            for (JsonNode a : m.path("aliases")) {
                String s = text(a);
                if (s != null && !s.isBlank() && s.length() <= 200 && aliases.size() < MAX_ALIASES) {
                    aliases.add(s);
                }
            }
            mentions.add(new Mention(name, text(m.get("type")), aliases));
        }
        Set<String> predicates = new LinkedHashSet<>();
        for (JsonNode s : n.path("statements")) {
            String p = text(s.get("predicate"));
            if (p != null && p.length() <= 64) {
                predicates.add(p);
            }
        }
        return new Extraction(JSON.writeValueAsString(n), mentions, predicates);
    }

    private static String text(JsonNode n) {
        return n != null && n.isString() ? n.stringValue() : null;
    }
}
