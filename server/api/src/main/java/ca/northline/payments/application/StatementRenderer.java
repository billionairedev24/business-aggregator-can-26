package ca.northline.payments.application;

import ca.northline.shared.Bytes;

/** Outbound port (S-41): prints a {@link StatementDocument} as PDF (Apache PDFBox in {@code payments.infra}). */
public interface StatementRenderer {

    Bytes pdf(StatementDocument document);
}
