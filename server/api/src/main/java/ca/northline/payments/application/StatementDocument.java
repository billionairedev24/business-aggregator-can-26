package ca.northline.payments.application;

import java.util.List;

/**
 * A statement laid out for print, already in its language: a title, who it is for, one table (header, month rows, a
 * total row) and notes. {@link StatementRenderer} turns it into a PDF.
 *
 * @param languageTag {@code en-CA} or {@code fr-CA} (the PDF's language, for screen readers)
 * @param party lines under the title: legal name, trading name, GST/HST number, province
 * @param footer the generation line ("Generated Oct 1, 2026 · Northline")
 */
public record StatementDocument(
        String languageTag,
        String title,
        List<String> party,
        List<String> columns,
        List<List<String>> rows,
        List<String> total,
        List<String> notes,
        String footer) {

    public StatementDocument {
        party = List.copyOf(party);
        columns = List.copyOf(columns);
        rows = rows.stream().map(List::copyOf).toList();
        total = List.copyOf(total);
        notes = List.copyOf(notes);
    }
}
