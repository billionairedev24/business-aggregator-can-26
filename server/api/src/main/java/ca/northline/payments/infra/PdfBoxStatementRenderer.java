package ca.northline.payments.infra;

import ca.northline.payments.application.StatementDocument;
import ca.northline.payments.application.StatementRenderer;
import ca.northline.shared.Bytes;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

/**
 * {@link StatementRenderer} with Apache PDFBox (S-41): US Letter, Helvetica (a standard PDF font, nothing embedded —
 * its WinAnsi encoding covers English and French), a title, the party lines, one table with right-aligned amounts and
 * a bold total, notes, and a footer with page numbers. Rows that don't fit continue on a new page under the header.
 */
@Component
class PdfBoxStatementRenderer implements StatementRenderer {

    private static final PDRectangle PAGE = PDRectangle.LETTER;
    private static final float MARGIN = 54;
    private static final float ROW = 18;
    private static final float TEXT = 9.5f;

    @Override
    public Bytes pdf(StatementDocument d) {
        try (var doc = new PDDocument();
                var out = new ByteArrayOutputStream()) {
            var regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            var info = new PDDocumentInformation();
            info.setTitle(d.title());
            info.setAuthor("Northline");
            info.setCreator("Northline Studio");
            doc.setDocumentInformation(info);
            doc.getDocumentCatalog().setLanguage(d.languageTag());

            var pages = new ArrayList<PDPage>();
            var width = PAGE.getWidth() - 2 * MARGIN;
            var widths = columnWidths(d.columns().size(), width);
            var page = newPage(doc, pages);
            var cs = new PDPageContentStream(doc, page);
            float y = PAGE.getHeight() - MARGIN;

            y = text(cs, bold, 18, MARGIN, y - 18, d.title()) - 10;
            for (var line : d.party()) {
                y = text(cs, regular, 10, MARGIN, y - 13, line);
            }
            y -= 18;
            y = header(cs, bold, d.columns(), widths, y);
            for (var row : d.rows()) {
                if (y - ROW < MARGIN + 60) {
                    cs.close();
                    page = newPage(doc, pages);
                    cs = new PDPageContentStream(doc, page);
                    y = header(cs, bold, d.columns(), widths, PAGE.getHeight() - MARGIN);
                }
                y = row(cs, regular, row, widths, y);
            }
            line(cs, MARGIN, y + 4, MARGIN + width);
            y = row(cs, bold, d.total(), widths, y - 2) - 14;
            for (var note : d.notes()) {
                for (var wrapped : wrap(note, regular, TEXT, width)) {
                    y = text(cs, regular, TEXT, MARGIN, y - 12, wrapped);
                }
            }
            cs.close();

            for (int i = 0; i < pages.size(); i++) {
                try (var footer =
                        new PDPageContentStream(doc, pages.get(i), PDPageContentStream.AppendMode.APPEND, true)) {
                    var label = d.footer() + "  ·  " + (i + 1) + " / " + pages.size();
                    text(footer, regular, 8, MARGIN, MARGIN - 24, label);
                }
            }
            doc.save(out);
            return Bytes.of(out.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write the statement PDF", e);
        }
    }

    private static PDPage newPage(PDDocument doc, List<PDPage> pages) {
        var page = new PDPage(PAGE);
        doc.addPage(page);
        pages.add(page);
        return page;
    }

    /** First column (month) wider; the amounts share the rest. */
    private static float[] columnWidths(int columns, float width) {
        var out = new float[columns];
        var first = width * 0.24f;
        out[0] = first;
        for (int i = 1; i < columns; i++) {
            out[i] = (width - first) / (columns - 1);
        }
        return out;
    }

    private static float header(PDPageContentStream cs, PDFont bold, List<String> columns, float[] widths, float y)
            throws IOException {
        var next = row(cs, bold, columns, widths, y);
        line(cs, MARGIN, next + 4, MARGIN + sum(widths));
        return next - 2;
    }

    /** One table row; amounts right-aligned in their column, the first cell left-aligned. Long headers shrink. */
    private static float row(PDPageContentStream cs, PDFont font, List<String> cells, float[] widths, float y)
            throws IOException {
        float x = MARGIN;
        for (int i = 0; i < cells.size() && i < widths.length; i++) {
            var cell = cells.get(i);
            var size = fit(cell, font, TEXT, widths[i] - 6);
            var w = font.getStringWidth(cell) / 1000 * size;
            var at = i == 0 ? x : x + widths[i] - w;
            text(cs, font, size, at, y - ROW + 5, cell);
            x += widths[i];
        }
        return y - ROW;
    }

    private static float fit(String text, PDFont font, float size, float width) throws IOException {
        var w = font.getStringWidth(text) / 1000 * size;
        return w <= width ? size : Math.max(6f, size * width / w);
    }

    private static float text(PDPageContentStream cs, PDFont font, float size, float x, float y, String text)
            throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(text);
        cs.endText();
        return y;
    }

    private static void line(PDPageContentStream cs, float x1, float y, float x2) throws IOException {
        cs.setLineWidth(0.5f);
        cs.moveTo(x1, y);
        cs.lineTo(x2, y);
        cs.stroke();
    }

    private static List<String> wrap(String text, PDFont font, float size, float width) throws IOException {
        var lines = new ArrayList<String>();
        var current = new StringBuilder();
        for (var word : text.split(" ")) {
            var candidate = current.isEmpty() ? word : current + " " + word;
            if (font.getStringWidth(candidate) / 1000 * size > width && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static float sum(float[] values) {
        float s = 0;
        for (var v : values) {
            s += v;
        }
        return s;
    }
}
