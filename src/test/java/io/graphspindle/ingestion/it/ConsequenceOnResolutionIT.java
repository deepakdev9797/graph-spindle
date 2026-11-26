package io.graphspindle.ingestion.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractIngestionIT;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * M7 (deferred from M5): accepting an incoming REPLACES claim through conflict resolution runs the
 * consequence rule, so the newly replaced decision's status becomes SUPERSEDED (SCHEMA §10.3).
 */
class ConsequenceOnResolutionIT extends AbstractIngestionIT {

    private List<String> status(String brain, String decision) {
        return query("""
                MATCH (d:Entity {brainId: $b, name: $n})<-[:SUBJECT]-(c:Claim {brainId: $b, predicate: 'status'})
                RETURN c.value + '/' + c.status + '/' + c.origin AS s ORDER BY c.recordedAt""",
                Map.of("b", brain, "n", decision)).stream().map(r -> (String) r.get("s")).toList();
    }

    @Test
    void acceptIncomingReplacesSupersedesTheReplacedDecision() {
        String d1 = k.entity("dev", "Decision", "Use Apache 2.0");
        String d2 = k.entity("dev", "Decision", "Use MIT");
        String d3 = k.entity("dev", "Decision", "Use BSD");
        k.relation("dev", d2, "REPLACES", d1);
        assertThat(status("dev", "Use Apache 2.0")).containsExactly("SUPERSEDED/ACTIVE/SYSTEM");
        // REPLACES is cardinality ONE: a second target is a contradiction, stored DISPUTED
        var disputed = k.assertRelation("dev", d2, "REPLACES", d3);
        assertThat(disputed.status()).as(String.valueOf(disputed.body())).isIn(200, 201);
        List<String> open = openConflicts("dev", "CONTRADICTION");
        assertThat(open).hasSize(1);
        assertThat(status("dev", "Use BSD")).isEmpty();

        var r = resolve("dev", open.get(0), "ACCEPT_INCOMING");
        assertThat(r.status()).as(String.valueOf(r.body())).isEqualTo(200);
        assertThat(status("dev", "Use BSD")).containsExactly("SUPERSEDED/ACTIVE/SYSTEM");
        assertThat(r.body().toString()).contains(query("MATCH (d:Entity {brainId:'dev', name:'Use BSD'})"
                + "<-[:SUBJECT]-(c:Claim {predicate:'status'}) RETURN c.id AS id", Map.of()).get(0).get("id")
                .toString());
        assertThat(count("MATCH (n:Entity {brainId:'other', type:'Decision'}) RETURN count(n)", Map.of())).isZero();
        assertOtherBrainUntouched();
    }
}
