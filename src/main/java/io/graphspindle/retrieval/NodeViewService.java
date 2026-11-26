package io.graphspindle.retrieval;

import io.graphspindle.brain.BrainService;
import io.graphspindle.common.Actor;
import io.graphspindle.common.BrainScope;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Paging;
import io.graphspindle.graph.ClaimView;
import io.graphspindle.graph.EntityView;
import io.graphspindle.graph.GraphReader;
import io.graphspindle.graph.SourceView;
import io.graphspindle.graph.TimedClaim;
import io.graphspindle.knowledge.KnowledgeModel.AliasInfo;
import io.graphspindle.knowledge.KnowledgeModel.SourceInfo;
import io.graphspindle.knowledge.KnowledgeViews;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

/**
 * Plain node views (ADR-024, ADR-026): an entity as node fields, properties and relationships, with
 * claims hidden. The current view reads the projection; {@link #asOf} and {@link #history}
 * reconstruct what the brain believed from the claim timeline in <em>record time</em>. Shared by
 * REST and MCP (M8).
 *
 * <p>Record-time belief: a positive claim counts from its {@code recordedAt}; a claim now
 * SUPERSEDED, RETRACTED or DISPUTED stops counting at its {@code statusChangedAt}, or for a
 * superseded claim at the {@code recordedAt} of its replacement (a claim that ended at the instant it
 * was recorded never counted). Intermediate status
 * changes are not stored, so a claim that is ACTIVE now counts from {@code recordedAt} even if it was
 * disputed for a while. World time ({@code validFrom}/{@code validTo}) is reported, not filtered on.
 */
@Service
public class NodeViewService {

    static final int MAX_RELATIONSHIPS = 200;
    /** Claims read for one timeline; beyond this the result is marked truncated. */
    static final int MAX_TIMELINE_CLAIMS = 2_000;
    public static final int DEFAULT_HISTORY_LIMIT = 200;
    public static final int MAX_HISTORY_LIMIT = 1_000;
    static final int SOURCES_PER_CHANGE = 3;

    private final BrainService brains;
    private final GraphReader reader;

    public NodeViewService(BrainService brains, GraphReader reader) {
        this.brains = brains;
        this.reader = reader;
    }

    /**
     * A relationship of the entity.
     *
     * @param type the relationship type (relation predicate)
     * @param direction {@code OUT} when the entity is the subject, {@code IN} when it is the object
     * @param entityId the other entity
     */
    public record Relationship(String type, String direction, String entityId, String entityName, String entityType,
            String validFrom, String validTo) {
    }

    /**
     * An entity as a plain node.
     *
     * @param asOf the point in time (record time) the view describes, or null for "now"
     * @param redirectedFrom the requested id when it was MERGED into this entity, else null
     * @param properties attribute values without the {@code attr_} prefix (ONE: the value, MANY: a sorted list)
     * @param truncated true when the relationships or the timeline hit their caps
     */
    public record NodeView(String id, String type, String name, String description, String status, String origin,
            String redirectedFrom, Instant asOf, Map<String, Object> properties, List<Relationship> relationships,
            List<AliasInfo> aliases, boolean truncated, boolean untrusted) {
    }

    /**
     * One change of a property or relationship.
     *
     * @param at record time of the change
     * @param kind {@code PROPERTY} or {@code RELATIONSHIP}
     * @param change {@code ADDED}, {@code CHANGED} or {@code REMOVED}
     * @param name property name or relationship type
     * @param direction for relationships: {@code OUT} or {@code IN}
     * @param oldValue previous property value, or null
     * @param newValue new property value, or null
     * @param claimId the claim that caused the change
     * @param claimStatus that claim's current status
     * @param reason the supersede or status reason, if any
     * @param sources up to 3 sources cited by the claim
     */
    public record Change(Instant at, String kind, String change, String name, String direction, Object oldValue,
            Object newValue, String entityId, String entityName, String claimId, String claimStatus, String origin,
            String ingestionId, String principal, String reason, String validFrom, String validTo,
            List<SourceInfo> sources) {
    }

    /** The chronological change history of an entity (oldest first; {@code truncated} when older entries were cut). */
    public record NodeHistory(String entityId, String redirectedFrom, List<Change> changes, boolean truncated,
            boolean untrusted) {
    }

    // ---- entry points -----------------------------------------------------------------------

    /** The entity now: projected properties and ACTIVE relationships. */
    public NodeView current(Actor actor, String brainId, String entityId) {
        BrainScope scope = brains.requireActive(brainId);
        EntityView e = resolve(scope, entityId);
        List<ClaimView> active = reader.claimsOfEntity(scope, e.id(), List.of("ACTIVE"), MAX_RELATIONSHIPS + 1);
        List<ClaimView> relations = active.stream().filter(c -> c.relation() && positive(c)).toList();
        boolean truncated = active.size() > MAX_RELATIONSHIPS;
        Map<String, Object> properties = new TreeMap<>(e.attributes());
        return node(scope, e, entityId, null, properties, relationships(scope, e.id(), relations), truncated);
    }

    /** What the brain believed about the entity at {@code asOf} (record time). */
    public NodeView asOf(Actor actor, String brainId, String entityId, Instant asOf) {
        Objects.requireNonNull(asOf, "asOf");
        BrainScope scope = brains.requireActive(brainId);
        EntityView e = resolve(scope, entityId);
        Instant created = reader.entityCreatedAt(scope, e.id()).orElse(null);
        if (created != null && created.isAfter(asOf)) {
            throw GraphSpindleException.notFound("entity at " + asOf, entityId);
        }
        List<TimedClaim> timeline = reader.claimTimeline(scope, e.id(), MAX_TIMELINE_CLAIMS + 1);
        boolean truncated = timeline.size() > MAX_TIMELINE_CLAIMS;
        List<TimedClaim> believed = believedAt(timeline.stream().limit(MAX_TIMELINE_CLAIMS).toList(), asOf);
        Map<String, Object> properties = properties(believed, e.id());
        List<ClaimView> relations = believed.stream().map(TimedClaim::claim).filter(ClaimView::relation).toList();
        return node(scope, e, entityId, asOf, properties, relationships(scope, e.id(), relations), truncated);
    }

    /** The claims believed at {@code asOf} (for {@code include=claims} on a point-in-time read). */
    public List<ClaimView> claimsAsOf(BrainScope scope, String entityId, Instant asOf) {
        return believedAt(reader.claimTimeline(scope, entityId, MAX_TIMELINE_CLAIMS), asOf).stream()
                .map(TimedClaim::claim).toList();
    }

    /** The entity's property and relationship changes in record time, oldest first. */
    public NodeHistory history(Actor actor, String brainId, String entityId, Integer limit) {
        BrainScope scope = brains.requireActive(brainId);
        int max = Paging.limit(limit, DEFAULT_HISTORY_LIMIT, MAX_HISTORY_LIMIT);
        EntityView e = resolve(scope, entityId);
        List<TimedClaim> timeline = reader.claimTimeline(scope, e.id(), MAX_TIMELINE_CLAIMS + 1);
        boolean truncated = timeline.size() > MAX_TIMELINE_CLAIMS;
        List<PendingChange> pending = replay(timeline.stream().limit(MAX_TIMELINE_CLAIMS).toList(), e.id());
        if (pending.size() > max) {
            pending = pending.subList(pending.size() - max, pending.size());
            truncated = true;
        }
        Set<String> ids = new LinkedHashSet<>();
        pending.forEach(p -> {
            if (p.otherId() != null) {
                ids.add(p.otherId());
            }
        });
        Map<String, EntityView> others = reader.entitiesByIds(scope, ids);
        Map<String, List<SourceView>> sources = reader.citedSources(scope,
                pending.stream().map(p -> p.cause().claim().id()).distinct().toList(), SOURCES_PER_CHANGE);
        List<Change> changes = new ArrayList<>();
        for (PendingChange p : pending) {
            ClaimView c = p.cause().claim();
            EntityView o = p.otherId() == null ? null : others.get(p.otherId());
            String reason = p.ended() ? c.statusReason() : p.cause().supersedeReason();
            changes.add(new Change(p.at(), p.kind(), p.change(), p.name(), p.direction(), p.oldValue(), p.newValue(),
                    p.otherId(), o == null ? null : o.name(), c.id(), c.status(), c.origin(),
                    c.createdByIngestionId(), c.createdByPrincipal(), reason, c.validFrom(), c.validTo(),
                    sources.getOrDefault(c.id(), List.of()).stream().map(KnowledgeViews::source).toList()));
        }
        String redirected = e.id().equals(entityId) ? null : entityId;
        return new NodeHistory(e.id(), redirected, changes, truncated, true);
    }

    // ---- asOf parsing -----------------------------------------------------------------------

    /**
     * Parses {@code asOf}: an ISO instant or offset date-time is taken as is, a local date-time as
     * UTC, and a date ({@code YYYY-MM-DD}) as the end of that day in UTC (everything recorded on it).
     */
    public static Instant parseAsOf(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > 40) {
            throw GraphSpindleException.validation("asOf must be an ISO date or date-time");
        }
        try {
            if (v.length() == 10) {
                return LocalDate.parse(v).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().minusNanos(1);
            }
            if (v.endsWith("Z") || v.matches(".*[+-]\\d{2}:\\d{2}$")) {
                return OffsetDateTime.parse(v).toInstant();
            }
            return LocalDateTime.parse(v).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException e) {
            throw GraphSpindleException.validation("asOf must be an ISO date (2026-11-20) or date-time "
                    + "(2026-11-20T10:15:30Z)");
        }
    }

    // ---- belief and replay ------------------------------------------------------------------

    /** The claims of the timeline believed at {@code at}. */
    static List<TimedClaim> believedAt(List<TimedClaim> timeline, Instant at) {
        Map<String, Instant> ends = ends(timeline);
        return timeline.stream().filter(t -> {
            Instant end = ends.get(t.claim().id());
            return startsBelief(t, end) && !t.recordedAt().isAfter(at) && (end == null || at.isBefore(end));
        }).toList();
    }

    /**
     * When each claim stopped being believed (absent while ACTIVE): its {@code statusChangedAt}, or,
     * for a superseded claim, the {@code recordedAt} of the claim that superseded it, so that the
     * replacement takes over at one instant.
     */
    static Map<String, Instant> ends(List<TimedClaim> timeline) {
        Map<String, Instant> ends = new java.util.HashMap<>();
        for (TimedClaim t : timeline) {
            switch (t.claim().status()) {
                case "SUPERSEDED", "RETRACTED", "DISPUTED" -> ends.put(t.claim().id(), t.statusChangedAt());
                default -> { }
            }
        }
        for (TimedClaim t : timeline) {
            for (String old : t.supersedes()) {
                if (ends.containsKey(old)) {
                    ends.put(old, t.recordedAt());
                }
            }
        }
        return ends;
    }

    /** Whether the claim was ever believed: positive, and not ended in the instant it was recorded. */
    private static boolean startsBelief(TimedClaim t, Instant end) {
        return positive(t.claim()) && (end == null || end.isAfter(t.recordedAt()));
    }

    private static boolean positive(ClaimView c) {
        return !"NEGATIVE".equals(c.polarity());
    }

    /** A change before names and sources are attached. */
    private record PendingChange(Instant at, String kind, String change, String name, String direction,
            Object oldValue, Object newValue, String otherId, TimedClaim cause, boolean ended) {
    }

    private record Event(Instant at, TimedClaim claim, boolean start) {
    }

    /** Replays belief start/end events in record time and diffs the state after each instant. */
    private static List<PendingChange> replay(List<TimedClaim> timeline, String entityId) {
        List<Event> events = new ArrayList<>();
        Map<String, Instant> ends = ends(timeline);
        for (TimedClaim t : timeline) {
            Instant end = ends.get(t.claim().id());
            if (!startsBelief(t, end)) {
                continue;
            }
            events.add(new Event(t.recordedAt(), t, true));
            if (end != null) {
                events.add(new Event(end, t, false));
            }
        }
        events.sort(Comparator.comparing(Event::at));
        List<PendingChange> out = new ArrayList<>();
        List<TimedClaim> believed = new ArrayList<>();
        int i = 0;
        while (i < events.size()) {
            Instant at = events.get(i).at();
            List<Event> batch = new ArrayList<>();
            while (i < events.size() && events.get(i).at().equals(at)) {
                batch.add(events.get(i++));
            }
            Map<String, Object> beforeProps = properties(believed, entityId);
            Map<String, TimedClaim> beforeRels = relationKeys(believed, entityId);
            for (Event ev : batch) {
                if (ev.start()) {
                    believed.add(ev.claim());
                } else {
                    believed.remove(ev.claim());
                }
            }
            Map<String, Object> afterProps = properties(believed, entityId);
            Map<String, TimedClaim> afterRels = relationKeys(believed, entityId);
            Set<String> names = new TreeSet<>(beforeProps.keySet());
            names.addAll(afterProps.keySet());
            for (String name : names) {
                Object before = beforeProps.get(name);
                Object after = afterProps.get(name);
                if (Objects.equals(before, after)) {
                    continue;
                }
                String change = before == null ? "ADDED" : after == null ? "REMOVED" : "CHANGED";
                Event cause = cause(batch, name, after != null);
                out.add(new PendingChange(at, "PROPERTY", change, name, null, before, after, null, cause.claim(),
                        !cause.start()));
            }
            for (Map.Entry<String, TimedClaim> e : afterRels.entrySet()) {
                if (!beforeRels.containsKey(e.getKey())) {
                    out.add(relationChange(at, "ADDED", e.getValue(), entityId, false));
                }
            }
            for (Map.Entry<String, TimedClaim> e : beforeRels.entrySet()) {
                if (!afterRels.containsKey(e.getKey())) {
                    out.add(relationChange(at, "REMOVED", e.getValue(), entityId, true));
                }
            }
        }
        return out;
    }

    /** The batch event explaining a property change: a starting claim when a value exists afterwards. */
    private static Event cause(List<Event> batch, String predicate, boolean preferStart) {
        Event fallback = null;
        for (Event ev : batch) {
            if (!ev.claim().claim().predicate().equals(predicate) || ev.claim().claim().relation()) {
                continue;
            }
            if (ev.start() == preferStart) {
                return ev;
            }
            fallback = ev;
        }
        return fallback;
    }

    private static PendingChange relationChange(Instant at, String change, TimedClaim t, String entityId,
            boolean ended) {
        ClaimView c = t.claim();
        boolean out = entityId.equals(c.subjectId());
        return new PendingChange(at, "RELATIONSHIP", change, c.predicate(), out ? "OUT" : "IN", null, null,
                out ? c.objectId() : c.subjectId(), t, ended);
    }

    private static Map<String, TimedClaim> relationKeys(Collection<TimedClaim> believed, String entityId) {
        Map<String, TimedClaim> keys = new LinkedHashMap<>();
        for (TimedClaim t : believed) {
            ClaimView c = t.claim();
            if (c.relation()) {
                boolean out = entityId.equals(c.subjectId());
                keys.putIfAbsent(c.predicate() + (out ? ">" : "<") + (out ? c.objectId() : c.subjectId()), t);
            }
        }
        return keys;
    }

    /** Attribute values of the believed claims where the entity is the subject (ONE: newest, MANY: sorted). */
    private static Map<String, Object> properties(Collection<TimedClaim> believed, String entityId) {
        Map<String, List<TimedClaim>> byPredicate = new TreeMap<>();
        for (TimedClaim t : believed) {
            ClaimView c = t.claim();
            if (!c.relation() && entityId.equals(c.subjectId()) && c.value() != null) {
                byPredicate.computeIfAbsent(c.predicate(), k -> new ArrayList<>()).add(t);
            }
        }
        Map<String, Object> out = new TreeMap<>();
        byPredicate.forEach((predicate, claims) -> {
            if ("MANY".equals(claims.get(0).claim().cardinality())) {
                out.put(predicate, List.copyOf(new TreeSet<>(claims.stream().map(t -> t.claim().value()).toList())));
            } else {
                out.put(predicate, claims.stream().max(Comparator.comparing(TimedClaim::recordedAt)
                        .thenComparing(t -> t.claim().id())).orElseThrow().claim().value());
            }
        });
        return out;
    }

    // ---- helpers ----------------------------------------------------------------------------

    private EntityView resolve(BrainScope scope, String entityId) {
        return (Paging.plausibleId(entityId) ? reader.resolveEntity(scope, entityId)
                : java.util.Optional.<EntityView>empty())
                .orElseThrow(() -> GraphSpindleException.notFound("entity", entityId));
    }

    private NodeView node(BrainScope scope, EntityView e, String requestedId, Instant asOf,
            Map<String, Object> properties, List<Relationship> relationships, boolean truncated) {
        List<AliasInfo> aliases = reader.aliases(scope, e.id()).stream().map(KnowledgeViews::alias).toList();
        String redirected = e.id().equals(requestedId) ? null : requestedId;
        return new NodeView(e.id(), e.type(), e.name(), e.description(), e.status(), e.origin(), redirected, asOf,
                properties, relationships, aliases, truncated || relationships.size() > MAX_RELATIONSHIPS, true);
    }

    private List<Relationship> relationships(BrainScope scope, String entityId, List<ClaimView> relations) {
        List<ClaimView> limited = relations.stream().filter(NodeViewService::positive).limit(MAX_RELATIONSHIPS)
                .toList();
        Set<String> ids = new LinkedHashSet<>();
        limited.forEach(c -> ids.add(entityId.equals(c.subjectId()) ? c.objectId() : c.subjectId()));
        Map<String, EntityView> others = reader.entitiesByIds(scope, ids);
        List<Relationship> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ClaimView c : limited) {
            boolean outgoing = entityId.equals(c.subjectId());
            String other = outgoing ? c.objectId() : c.subjectId();
            if (!seen.add(c.predicate() + (outgoing ? ">" : "<") + other)) {
                continue;
            }
            EntityView o = others.get(other);
            out.add(new Relationship(c.predicate(), outgoing ? "OUT" : "IN", other, o == null ? null : o.name(),
                    o == null ? null : o.type(), c.validFrom(), c.validTo()));
        }
        out.sort(Comparator.comparing(Relationship::type).thenComparing(Relationship::direction)
                .thenComparing(r -> String.valueOf(r.entityName())).thenComparing(Relationship::entityId));
        return out;
    }
}
