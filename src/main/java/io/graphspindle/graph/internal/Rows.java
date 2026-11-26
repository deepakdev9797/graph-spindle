package io.graphspindle.graph.internal;

import io.graphspindle.graph.ClaimView;
import io.graphspindle.graph.ConflictView;
import io.graphspindle.graph.EntityView;
import io.graphspindle.graph.IngestionRecord;
import io.graphspindle.graph.SourceView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.neo4j.driver.Value;

/** Mapping of driver values to graph records. */
public final class Rows {

    private Rows() {
    }

    public static EntityView entity(Value n) {
        Map<String, Object> attrs = new TreeMap<>();
        for (String k : n.keys()) {
            if (k.startsWith("attr_")) {
                attrs.put(k.substring(5), n.get(k).asObject());
            }
        }
        return new EntityView(n.get("id").asString(), n.get("type").asString(), n.get("name").asString(null),
                n.get("normalizedName").asString(null), n.get("canonicalKey").asString(),
                n.get("description").asString(null), n.get("descriptionLocked").asBoolean(false),
                n.get("status").asString(), n.get("origin").asString(null), n.get("mentionCount").asLong(0),
                n.get("mergedIntoId").asString(null), attrs);
    }

    public static ClaimView claim(Value c) {
        return new ClaimView(c.get("id").asString(), c.get("kind").asString(), c.get("predicate").asString(),
                c.get("subjectId").asString(), c.get("objectId").asString(null), c.get("valueType").asString(null),
                c.get("value").asString(null), c.get("valueNorm").asString(null), c.get("polarity").asString(),
                c.get("statement").asString(null), c.get("status").asString(), c.get("confidence").asDouble(0),
                c.get("origin").asString(), c.get("cardinality").asString(null), c.get("activeKey").asString(null),
                c.get("slotKey").asString(null), c.get("evidenceCount").asLong(0), c.get("validFrom").asString(null),
                c.get("validTo").asString(null), c.get("createdByPrincipal").asString(null),
                c.get("createdByIngestionId").asString(null), c.get("statusReason").asString(null));
    }

    public static IngestionRecord ingestion(Value i) {
        return new IngestionRecord(i.get("id").asString(), i.get("requestId").asString(),
                i.get("contentHash").asString(), i.get("contentType").asString(), i.get("contentLength").asInt(0),
                i.get("summary").asString(null), i.get("speakerType").asString(null),
                i.get("speakerName").asString(null), i.get("principal").asString(), i.get("status").asString(),
                i.get("opsProposed").asInt(0), i.get("opsApplied").asInt(0), i.get("opsRejected").asInt(0),
                i.get("report").asString(null), i.get("llmProvider").asString(null), i.get("llmModel").asString(null),
                i.get("promptVersion").asString(null), instant(i.get("occurredAt")), instant(i.get("receivedAt")),
                instant(i.get("completedAt")), i.get("rawContent").asString(null));
    }

    public static SourceView source(Value s) {
        return new SourceView(s.get("id").asString(), s.get("kind").asString(), s.get("locator").asString(),
                s.get("title").asString(null), s.get("summary").asString(null), s.get("mediaType").asString(null),
                instant(s.get("createdAt")), instant(s.get("lastSeenAt")));
    }

    /** {@code x} is the Conflict node, {@code involves} a list of {role, id, label} maps. */
    public static ConflictView conflict(Value x, Value involves, Value raisedBy) {
        List<ConflictView.Involved> list = new ArrayList<>();
        for (Value i : involves.values()) {
            if (!i.get("id").isNull()) {
                list.add(new ConflictView.Involved(i.get("role").asString(null), i.get("label").asString(),
                        i.get("id").asString()));
            }
        }
        list.sort(java.util.Comparator.comparing(ConflictView.Involved::role,
                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()))
                .thenComparing(ConflictView.Involved::id));
        return new ConflictView(x.get("id").asString(), x.get("kind").asString(), x.get("status").asString(),
                x.get("description").asString(null), instant(x.get("createdAt")), instant(x.get("resolvedAt")),
                x.get("resolution").asString(null), x.get("resolvedBy").asString(null),
                x.get("resolutionNote").asString(null), raisedBy == null || raisedBy.isNull() ? null : raisedBy.asString(),
                list);
    }

    static Instant instant(Value v) {
        return v == null || v.isNull() ? null : v.asZonedDateTime().toInstant();
    }
}
