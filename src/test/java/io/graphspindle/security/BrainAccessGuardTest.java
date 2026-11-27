package io.graphspindle.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.graphspindle.common.Actor;
import io.graphspindle.common.BrainAccessGuard;
import io.graphspindle.common.ErrorCode;
import io.graphspindle.common.GraphSpindleException;
import io.graphspindle.common.Permission;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

class BrainAccessGuardTest {

    static final Actor KEY = Actor.key("0123456789abcdef", Map.of("a", Permission.READ, "b", Permission.WRITE,
            "c", Permission.ADMIN));

    @ParameterizedTest
    @CsvSource({"a,READ,", "a,WRITE,FORBIDDEN", "a,ADMIN,FORBIDDEN", "b,READ,", "b,WRITE,", "b,ADMIN,FORBIDDEN",
        "c,READ,", "c,WRITE,", "c,ADMIN,", "z,READ,BRAIN_NOT_FOUND", "z,ADMIN,BRAIN_NOT_FOUND"})
    void permissionMatrix(String brain, Permission required, ErrorCode expected) {
        if (expected == null) {
            assertThatCode(() -> BrainAccessGuard.require(KEY, brain, required)).doesNotThrowAnyException();
        } else {
            assertThatThrownBy(() -> BrainAccessGuard.require(KEY, brain, required))
                    .isInstanceOfSatisfying(GraphSpindleException.class, e -> assertThat(e.code()).isEqualTo(expected));
        }
    }

    @Test
    void invisibleBrainLooksExactlyLikeAMissingOne() {
        assertThatThrownBy(() -> BrainAccessGuard.require(KEY, "secret-brain", Permission.READ))
                .hasMessage(GraphSpindleException.brainNotFound("secret-brain").getMessage());
    }

    @Test
    void adminSeesEverythingAndNoActorIsUnauthenticated() {
        assertThatCode(() -> BrainAccessGuard.require(Actor.LOCAL_ADMIN, "any", Permission.ADMIN))
                .doesNotThrowAnyException();
        assertThatCode(() -> BrainAccessGuard.requireGlobalAdmin(Actor.LOCAL_ADMIN)).doesNotThrowAnyException();
        assertThatThrownBy(() -> BrainAccessGuard.requireGlobalAdmin(KEY))
                .isInstanceOfSatisfying(GraphSpindleException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> BrainAccessGuard.require(null, "a", Permission.READ))
                .isInstanceOfSatisfying(GraphSpindleException.class,
                        e -> assertThat(e.code()).isEqualTo(ErrorCode.UNAUTHENTICATED));
    }
}
