package io.graphspindle.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.graphspindle.common.GraphSpindleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** ADR-031: mediaType and contentHash are stored as given, so only well-formed values are accepted. */
class SourceInputTest {

    @ParameterizedTest
    @ValueSource(strings = {"text/markdown", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "Image/PNG", "application/ld+json"})
    void mediaTypesAreAccepted(String mediaType) {
        assertThat(new SourceInput("FILE", "a.md", null, null, mediaType, null).mediaType())
                .isEqualTo(mediaType.toLowerCase(java.util.Locale.ROOT));
    }

    @ParameterizedTest
    @ValueSource(strings = {"sk-abcdefghijklmnopqrstuvwxyz012345", "text/plain; key=secret", "text", "a b/c",
            "text/plain/extra"})
    void otherMediaTypesAreRejected(String mediaType) {
        assertThatThrownBy(() -> new SourceInput("FILE", "a.md", null, null, mediaType, null))
                .isInstanceOf(GraphSpindleException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "sha256:E3B0C44298FC1C149AFBF4C8996FB92427AE41E4649B934CA495991B7852B855",
            "sha256-47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU=", "d41d8cd98f00b204e9800998ecf8427e"})
    void hashesAreAccepted(String hash) {
        assertThat(new SourceInput("FILE", "a.md", null, null, null, hash).clientContentHash()).isEqualTo(hash);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ghp_abcdefghijklmnopqrstuvwxyz0123456789", "sk-abcdefghijklmnopqrstuvwxyz012345",
            "password=hunter2", "abc", "sha256:xyz"})
    void otherHashesAreRejected(String hash) {
        assertThatThrownBy(() -> new SourceInput("FILE", "a.md", null, null, null, hash))
                .isInstanceOf(GraphSpindleException.class);
    }
}
