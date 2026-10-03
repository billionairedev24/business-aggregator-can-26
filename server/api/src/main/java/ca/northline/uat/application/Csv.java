package ca.northline.uat.application;

import java.util.List;
import java.util.stream.Collectors;

/** RFC 4180 rows, with a leading quote mark on cells a spreadsheet would read as a formula. */
final class Csv {

    private Csv() {}

    static String row(List<String> cells) {
        return cells.stream().map(Csv::cell).collect(Collectors.joining(",")) + "\r\n";
    }

    static String cell(String raw) {
        var value =
                !raw.isEmpty() && "=+-@\t\r".indexOf(raw.charAt(0)) >= 0 && !raw.matches("-?\\d+") ? "'" + raw : raw;
        return value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
                ? "\"" + value.replace("\"", "\"\"") + "\""
                : value;
    }
}
