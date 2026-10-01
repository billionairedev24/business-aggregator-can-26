package ca.northline.platform.i18n;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MessageCatalogueTest {

    private static final MessageCatalogue.Arguments SAME = MessageCatalogue.Arguments.AS_IS;
    private final MessageCatalogue catalogue = MessageCatalogue.frenchCanadian();

    @Test
    void packagedCatalogueHasTheSpecMessagesInFrench() {
        assertThat(catalogue.entries()).hasSizeGreaterThan(500);
        assertThat(catalogue.french("First name is required.", SAME)).hasValue("Le prénom est obligatoire.");
        assertThat(catalogue.french("Format is 9 digits + RT0001 (e.g. 123456789 RT0001).", SAME))
                .hasValue("Format : 9 chiffres + RT0001 (p. ex. 123456789 RT0001).");
    }

    @Test
    void englishStaysExactUnlessFrenchIsPreferred() {
        var en = "Describe this line — customers must see what they're paying for.";
        assertThat(catalogue.localize(en, null, SAME)).isEqualTo(en);
        assertThat(catalogue.localize(en, "en-CA,en;q=0.9,fr;q=0.8", SAME)).isEqualTo(en);
        assertThat(catalogue.localize(en, "fr-CA,fr;q=0.9,en;q=0.8", SAME))
                .isEqualTo("Décrivez cette ligne — les clients doivent voir ce qu’ils paient.");
        assertThat(catalogue.localize("Not in the catalogue.", "fr-CA", SAME)).isEqualTo("Not in the catalogue.");
    }

    @Test
    void acceptLanguageDecidesByWeight() {
        assertThat(MessageCatalogue.prefersFrench("fr")).isTrue();
        assertThat(MessageCatalogue.prefersFrench("fr-CA")).isTrue();
        assertThat(MessageCatalogue.prefersFrench("de-DE,fr;q=0.7,en;q=0.5")).isTrue();
        assertThat(MessageCatalogue.prefersFrench("en;q=0.9,fr;q=0.95")).isTrue();
        assertThat(MessageCatalogue.prefersFrench("en-CA,fr-CA;q=0.9")).isFalse();
        assertThat(MessageCatalogue.prefersFrench("*")).isFalse();
        assertThat(MessageCatalogue.prefersFrench("fr;q=0")).isFalse();
        assertThat(MessageCatalogue.prefersFrench("not a header;;")).isFalse();
        assertThat(MessageCatalogue.prefersFrench("")).isFalse();
    }

    @Test
    void templatesCarryTheirParts() {
        assertThat(catalogue.french("Only 3 left.", SAME)).hasValue("Plus que 3 en stock.");
        assertThat(catalogue.french("Maximum 10.", SAME)).hasValue("Maximum 10.");
        assertThat(catalogue.french("At most 80 characters.", SAME)).hasValue("Au plus 80 caractères.");
        assertThat(catalogue.french("Pick at least 2 for Sauces.", SAME))
                .hasValue("Choisissez au moins 2 pour Sauces.");
        assertThat(catalogue.french("Pick 2 for Sauces.", SAME)).hasValue("Choisissez 2 pour Sauces.");
    }

    @Test
    void amountsAndPlaceNamesAreWrittenTheFrenchWay() {
        assertThat(catalogue.french("You can pay out up to $1,234.50.", SAME))
                .hasValue("Vous pouvez verser jusqu’à 1 234,50 $.");
        MessageCatalogue.Arguments places = (name, form) ->
                name.equals("Nova Scotia") ? ("in".equals(form) ? "en Nouvelle-Écosse" : "Nouvelle-Écosse") : name;
        assertThat(catalogue.french("Northline isn't open in Nova Scotia yet.", places))
                .hasValue("Northline n’est pas encore offert en Nouvelle-Écosse.");
        assertThat(catalogue.french("This day is not a statutory holiday in Nova Scotia.", places))
                .hasValue("Ce jour n’est pas un jour férié en Nouvelle-Écosse.");
    }

    @Test
    void positionalAndNamedPlaceholdersCanMoveInTheFrench() {
        var c = MessageCatalogue.parse("""
                # test
                %s invited %s.\t%2$s a été invité par %1$s.\tnew
                {who} left {team}.\t{team} : {who} est parti.\treview
                """);
        assertThat(c.french("Ravi invited Jas.", SAME)).hasValue("Jas a été invité par Ravi.");
        assertThat(c.french("Jas left Prairie Wrench.", SAME)).hasValue("Prairie Wrench : Jas est parti.");
        assertThat(c.entries())
                .extracting(MessageCatalogue.Entry::status)
                .containsExactly(MessageCatalogue.Status.NEW, MessageCatalogue.Status.REVIEW);
    }

    @Test
    void brokenLinesAreRejected() {
        assertThatThrownBy(() -> MessageCatalogue.parse("Only English.\n")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MessageCatalogue.parse("A.\tB.\tnew\nA.\tC.\tnew\n"))
                .hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> MessageCatalogue.parse("Hi {name}.\tSalut {nom}.\tnew\n"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> MessageCatalogue.parse("%s and %s.\t%s.\tnew\n"))
                .isInstanceOf(IllegalStateException.class);
    }
}
