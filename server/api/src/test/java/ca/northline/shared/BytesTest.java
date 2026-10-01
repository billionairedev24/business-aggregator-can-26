package ca.northline.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class BytesTest {

    @Test
    void equalByContent_printedBySizeOnly() {
        var a = Bytes.of("%PDF-1.7 secret".getBytes(StandardCharsets.UTF_8));
        var b = Bytes.of("%PDF-1.7 secret".getBytes(StandardCharsets.UTF_8));
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b).isNotEqualTo(Bytes.of(new byte[] {1}));
        assertThat(a).hasToString("Bytes(size=15)");
        assertThat(a.toString()).doesNotContain("PDF", "secret");
    }

    @Test
    void copiesInAndOut() {
        var source = new byte[] {1, 2, 3};
        var bytes = Bytes.of(source);
        source[0] = 9;
        bytes.toArray()[1] = 9;
        assertThat(bytes.toArray()).containsExactly(1, 2, 3);
        assertThat(bytes.size()).isEqualTo(3);
        assertThat(Bytes.of(new byte[0]).isEmpty()).isTrue();
    }
}
