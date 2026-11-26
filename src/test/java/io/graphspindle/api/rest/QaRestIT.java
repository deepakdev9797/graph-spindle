package io.graphspindle.api.rest;

import static org.assertj.core.api.Assertions.assertThat;

import io.graphspindle.support.AbstractNeo4jIT;
import org.junit.jupiter.api.Test;

/** QA review of the M2 REST layer: each test CONFIRMS a finding (it fails on a426e70). */
class QaRestIT extends AbstractNeo4jIT {

    /** "Strict JSON": duplicate keys are silently last-wins. */
    @Test
    void duplicateJsonKeysAreRejected() {
        var r = api().post("/api/v1/brains", "{\"id\":\"qa-dup\",\"name\":\"first\",\"name\":\"second\"}");
        assertThat(r.status()).isEqualTo(400);
    }

    /** "Strict JSON": trailing content after the object is ignored. */
    @Test
    void trailingTokensAreRejected() {
        var r = api().post("/api/v1/brains", "{\"id\":\"qa-trail\",\"name\":\"n\"} {\"junk\":true}");
        assertThat(r.status()).isEqualTo(400);
    }

    /** No request-size cap: a 20 MB body is fully read and parsed before the 1000-char check. */
    @Test
    void oversizedBodyIsRejectedAsLimitExceeded() {
        String big = "x".repeat(20 * 1024 * 1024);
        var r = api().post("/api/v1/brains", "{\"id\":\"qa-big\",\"name\":\"n\",\"description\":\"" + big + "\"}");
        assertThat(r.status()).isEqualTo(413);
    }
}
