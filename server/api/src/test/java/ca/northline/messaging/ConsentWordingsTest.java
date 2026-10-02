package ca.northline.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.messaging.domain.ConsentCategory;
import ca.northline.messaging.domain.ConsentEvidence;
import ca.northline.messaging.domain.ConsentWordings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S-108: a consent record stores the wording version shown, so a published wording never changes — new words get a new
 * version, and its hash is pinned here when it is published. Also the evidence minimisation.
 */
class ConsentWordingsTest {

    /** version → SHA-256 of "en\nfr". Add a line for a new version; never change one. */
    static final Map<String, String> PUBLISHED = Map.of(
            "account.email.2026-10", "1c1f5ccca23e88920732b87fef7df1381cd1e75e5e85d30c2200328d5064c7b0",
            "account.sms.2026-10", "56ae9168947ada4ba50cf00af3940099d33c86ddbff90b21e41a3795d3eaf23d",
            "account.push.2026-10", "b9e683813abb45059967844e00e89d94ced3f7ed4ac2fd2b2f67e5c4d3a35d40",
            "studio.email.2026-10", "228c5c8c75ceba033ae84f185428de6ce52ef4f1fea52177b076ffb2c2e9354b");

    @Test
    void publishedWordings_neverChange_andEveryWordingIsPinned() throws Exception {
        for (var wording : ConsentWordings.all()) {
            var hash = HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest((wording.en() + "\n" + wording.fr()).getBytes(StandardCharsets.UTF_8)));
            assertThat(hash).as(wording.version()).isEqualTo(PUBLISHED.get(wording.version()));
        }
        assertThat(ConsentWordings.all()).hasSize(PUBLISHED.size());
    }

    @Test
    void eachWording_namesTheSender_theChannel_andHowToWithdraw_inBothLanguages() {
        for (var wording : ConsentWordings.all()) {
            var en = wording.text("en", "Example Legal Name Inc.");
            var fr = wording.text("fr", "Example Legal Name Inc.");
            assertThat(en).startsWith("Yes, Example Legal Name Inc.").containsAnyOf("withdraw", "turn them off");
            assertThat(fr).startsWith("Oui, Example Legal Name Inc.").containsAnyOf("retirer", "désactiver");
            assertThat(wording.version()).matches("[a-z0-9_.-]{3,64}");
        }
        assertThat(ConsentWordings.current(ConsentCategory.MARKETING_SMS, ConsentWordings.Surface.STUDIO))
                .isEmpty();
    }

    @Test
    void evidence_isMinimised() {
        var v4 = ConsentEvidence.of("203.0.113.42", "Mozilla/5.0");
        assertThat(v4.ipPrefix()).isEqualTo("203.0.113.0/24");
        assertThat(v4.userAgentHash()).hasSize(64).doesNotContain("Mozilla");
        assertThat(ConsentEvidence.of("2001:db8:1:2:3:4:5:6", null).ipPrefix()).isEqualTo("2001:db8:1::/48");
        assertThat(ConsentEvidence.of("2001:db8::1", null).ipPrefix()).isEqualTo("2001:db8::/48");
        assertThat(ConsentEvidence.of("not an address", "").ipPrefix()).isNull();
        assertThat(ConsentEvidence.of("::1", "").userAgentHash()).isNull();
        assertThat(ConsentEvidence.addressHash(" Amara@Example.ca "))
                .isEqualTo(ConsentEvidence.addressHash("amara@example.ca"));
    }
}
