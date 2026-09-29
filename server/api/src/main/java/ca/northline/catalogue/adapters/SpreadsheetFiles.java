package ca.northline.catalogue.adapters;

import ca.northline.catalogue.application.SpreadsheetReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads bulk-upload files without a spreadsheet library: RFC 4180 CSV (UTF-8, optional BOM, comma or semicolon) and
 * the first worksheet of an .xlsx (shared strings, inline strings, numbers). The first row is the header; keys are
 * trimmed and lower-cased.
 */
@Component
class SpreadsheetFiles implements SpreadsheetReader {

    /** Zip-bomb guard: no single part may inflate beyond this. */
    static final long MAX_PART_BYTES = 64L * 1024 * 1024;

    private static final XMLInputFactory XML = XMLInputFactory.newFactory();

    static {
        XML.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XML.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        XML.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    }

    @Override
    public List<Map<String, String>> read(String fileName, byte[] bytes) {
        var name = fileName.toLowerCase(Locale.ROOT);
        var zip = bytes.length > 3 && bytes[0] == 'P' && bytes[1] == 'K';
        if (name.endsWith(".xlsx") || zip) {
            return toRecords(xlsx(bytes));
        }
        if (name.endsWith(".csv") || name.endsWith(".txt")) {
            return toRecords(csv(new String(bytes, StandardCharsets.UTF_8)));
        }
        throw new UnreadableFile("Unsupported file type: " + fileName);
    }

    static List<Map<String, String>> toRecords(List<List<String>> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        var header = rows.getFirst().stream()
                .map(h -> h.strip().toLowerCase(Locale.ROOT))
                .toList();
        var out = new ArrayList<Map<String, String>>();
        for (var row : rows.subList(1, rows.size())) {
            var record = new LinkedHashMap<String, String>();
            for (int i = 0; i < header.size(); i++) {
                if (!header.get(i).isEmpty()) {
                    record.put(header.get(i), i < row.size() ? row.get(i) : "");
                }
            }
            out.add(record);
        }
        return out;
    }

    // ── CSV ────────────────────────────────────────────────────────────────────────────────────────────────────────

    static List<List<String>> csv(String text) {
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        var firstLine = text.lines().findFirst().orElse("");
        var sep = firstLine.chars().filter(c -> c == ';').count()
                        > firstLine.chars().filter(c -> c == ',').count()
                ? ';'
                : ',';
        var rows = new ArrayList<List<String>>();
        var row = new ArrayList<String>();
        var cell = new StringBuilder();
        var quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == sep) {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                rows.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    // ── XLSX ───────────────────────────────────────────────────────────────────────────────────────────────────────

    static List<List<String>> xlsx(byte[] bytes) {
        var parts = new HashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                var name = entry.getName();
                if (name.equals("xl/sharedStrings.xml") || name.startsWith("xl/worksheets/sheet")) {
                    parts.put(name, readLimited(zip));
                }
            }
        } catch (IOException ex) {
            throw new UnreadableFile("Not a readable .xlsx: " + ex.getMessage());
        }
        var sheet = parts.keySet().stream()
                .filter(n -> n.matches("xl/worksheets/sheet\\d+\\.xml"))
                .min((a, b) -> Integer.compare(sheetNumber(a), sheetNumber(b)))
                .orElseThrow(() -> new UnreadableFile("The workbook has no worksheet"));
        try {
            var shared = parts.containsKey("xl/sharedStrings.xml")
                    ? sharedStrings(parts.get("xl/sharedStrings.xml"))
                    : List.<String>of();
            return sheetRows(parts.get(sheet), shared);
        } catch (XMLStreamException ex) {
            throw new UnreadableFile("Not a readable .xlsx: " + ex.getMessage());
        }
    }

    private static int sheetNumber(String name) {
        return Integer.parseInt(name.replaceAll("\\D", ""));
    }

    private static byte[] readLimited(InputStream in) throws IOException {
        var out = new java.io.ByteArrayOutputStream();
        var buffer = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) > 0) {
            total += n;
            if (total > MAX_PART_BYTES) {
                throw new UnreadableFile("Workbook part too large");
            }
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static List<String> sharedStrings(byte @Nullable [] xml) throws XMLStreamException {
        var out = new ArrayList<String>();
        if (xml == null) {
            return out;
        }
        var r = XML.createXMLStreamReader(new ByteArrayInputStream(xml));
        StringBuilder current = null;
        while (r.hasNext()) {
            var event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT && r.getLocalName().equals("si")) {
                current = new StringBuilder();
            } else if (event == XMLStreamConstants.START_ELEMENT
                    && r.getLocalName().equals("t")
                    && current != null) {
                current.append(r.getElementText());
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && r.getLocalName().equals("si")
                    && current != null) {
                out.add(current.toString());
                current = null;
            }
        }
        return out;
    }

    private static List<List<String>> sheetRows(byte @Nullable [] xml, List<String> shared) throws XMLStreamException {
        var rows = new TreeMap<Integer, TreeMap<Integer, String>>();
        if (xml == null) {
            return List.of();
        }
        XMLStreamReader r = XML.createXMLStreamReader(new ByteArrayInputStream(xml));
        int rowIndex = 0;
        int colIndex = -1;
        String cellType = "";
        var value = new StringBuilder();
        var inCell = false;
        while (r.hasNext()) {
            var event = r.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "row" -> {
                        var attr = r.getAttributeValue(null, "r");
                        rowIndex = attr == null ? rowIndex + 1 : Integer.parseInt(attr);
                        colIndex = -1;
                    }
                    case "c" -> {
                        var ref = r.getAttributeValue(null, "r");
                        colIndex = ref == null ? colIndex + 1 : column(ref);
                        var t = r.getAttributeValue(null, "t");
                        cellType = t == null ? "" : t;
                        value.setLength(0);
                        inCell = true;
                    }
                    case "v", "t" -> {
                        if (inCell) {
                            value.append(r.getElementText());
                        }
                    }
                    default -> {
                        // formatting elements are ignored
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT
                    && r.getLocalName().equals("c")) {
                inCell = false;
                var text = switch (cellType) {
                    case "s" -> {
                        var i = Integer.parseInt(value.toString().strip());
                        yield i < shared.size() ? shared.get(i) : "";
                    }
                    case "inlineStr", "str", "b", "e" -> value.toString();
                    default -> number(value.toString());
                };
                rows.computeIfAbsent(rowIndex, _ -> new TreeMap<>()).put(colIndex, text);
            }
        }
        var out = new ArrayList<List<String>>();
        for (var row : rows.values()) {
            var width = row.isEmpty() ? 0 : row.lastKey() + 1;
            var cells = new ArrayList<String>();
            for (int i = 0; i < width; i++) {
                cells.add(row.getOrDefault(i, ""));
            }
            out.add(cells);
        }
        return out;
    }

    /** "B12" → 1. */
    static int column(String ref) {
        int col = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (!Character.isLetter(c)) {
                break;
            }
            col = col * 26 + (Character.toUpperCase(c) - 'A' + 1);
        }
        return col - 1;
    }

    /** Numbers as plain text: 19.99 stays, 2.8851200226E11 becomes 288512002260, 22.0 becomes 22. */
    static String number(String raw) {
        var s = raw.strip();
        if (s.isEmpty()) {
            return s;
        }
        try {
            return new BigDecimal(s).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException ex) {
            return s;
        }
    }
}
