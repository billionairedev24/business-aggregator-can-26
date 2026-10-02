package ca.northline.pci;

import ca.northline.platform.pci.CardData;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * S-110: looks for cardholder data (PANs, track data, verification codes, fields named like them — {@link CardData})
 * wherever Northline keeps or describes data: the database's columns (names and contents, every schema), the seed
 * files, the event and webhook JSON Schemas, the OpenAPI documents and the web and mobile sources. Used by
 * {@code CardDataScanTest} ({@code make pci-scan}) and by {@code CardDataGuardTest} ("never persisted").
 */
public final class CardDataScanner {

    /** The repository root, from the api module's working directory. */
    public static final Path REPO = Path.of("../..").toAbsolutePath().normalize();

    /** A finding: where (file:line, schema.table.column, OpenAPI pointer) and what. Never the value itself. */
    public record Finding(String where, String what) {
        @Override
        public String toString() {
            return where + ": " + what;
        }
    }

    /** One column of the database: schema, table, column, Postgres type. */
    public record Column(String schema, String table, String name, String type) {
        String qualified() {
            return schema + "." + table + "." + name;
        }
    }

    /** Columns whose long digit runs are barcodes, not cards (the request guard skips the same names). */
    private static final Set<String> BARCODE_COLUMNS = Set.of("gtin", "ean", "upc", "isbn", "barcode", "sku");

    /** Postgres types that could carry a card number as text or digits; {@code bytea} holds only sealed values. */
    private static final Set<String> SCANNED_TYPES = Set.of(
            "text", "character varying", "character", "json", "jsonb", "ARRAY", "bigint", "numeric", "USER-DEFINED");

    private static final Set<String> SYSTEM_SCHEMAS =
            Set.of("pg_catalog", "information_schema", "pg_toast", "topology", "tiger", "tiger_data");

    private static final Pattern EXPIRY = Pattern.compile("(?<!\\d)(0[1-9]|1[0-2]) ?/ ?(\\d{2}|20\\d{2})(?!\\d)");

    private CardDataScanner() {}

    // ---- database ---------------------------------------------------------------------------------------------

    public static List<Column> columns(JdbcClient jdbc) {
        return jdbc
                .sql("""
                        select table_schema, table_name, column_name, data_type
                          from information_schema.columns
                         where table_schema not like 'pg\\_%'
                         order by table_schema, table_name, ordinal_position
                        """)
                .query((rs, _) -> new Column(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)))
                .list()
                .stream()
                .filter(c -> !SYSTEM_SCHEMAS.contains(c.schema()))
                .filter(c -> !(c.schema().equals("public") && c.table().equals("spatial_ref_sys")))
                .toList();
    }

    /** Columns named like card data, in every schema (tables and views). */
    public static List<Finding> columnNames(List<Column> columns) {
        return columns.stream()
                .filter(c -> CardData.isCardDataName(c.name()))
                .map(c -> new Finding(c.qualified(), "column named like card data (" + c.type() + ")"))
                .toList();
    }

    /**
     * Every value of every text, JSON, array and numeric column of every base table that holds a PAN, track data or a
     * verification code ({@code accept} narrows to one value, e.g. the regression test's own number). An expiry next to
     * a PAN is called out: together they are cardholder data even without the CVC.
     */
    public static List<Finding> contents(JdbcClient jdbc, List<Column> columns, Predicate<String> accept) {
        var tables = jdbc.sql("""
                        select table_schema || '.' || table_name from information_schema.tables
                         where table_type = 'BASE TABLE'
                        """).query(String.class).set();
        var findings = new ArrayList<Finding>();
        for (var c : columns) {
            if (!tables.contains(c.schema() + "." + c.table()) || !SCANNED_TYPES.contains(c.type())) {
                continue;
            }
            var column = quote(c.name());
            var sql = "select " + column + "::text from " + quote(c.schema()) + "." + quote(c.table()) + " where "
                    + "%s::text ~ '[0-9]([ -]?[0-9]){12,18}' or %s::text ~* '(cvv|cvc|cvn|csc|security.?code)'"
                            .formatted(column, column)
                    + " limit 5000"; // coarse pre-filter: 13+ digits or a CVC word; CardData decides
            var barcode = BARCODE_COLUMNS.contains(c.name().toLowerCase(Locale.ROOT));
            for (var value : jdbc.sql(sql).query(String.class).list()) {
                var what = classify(value, barcode);
                if (what != null && accept.test(value)) {
                    findings.add(new Finding(c.qualified(), what));
                }
            }
        }
        return findings;
    }

    // ---- files ------------------------------------------------------------------------------------------------

    /** Lines of text files under {@code dirs} that hold card data (seeds, fixtures). */
    public static List<Finding> fileContents(List<Path> dirs, Predicate<Path> include) {
        var findings = new ArrayList<Finding>();
        for (var file : files(dirs, include)) {
            var lines = read(file).lines().toList();
            for (var i = 0; i < lines.size(); i++) {
                var what = classify(lines.get(i), false);
                if (what != null) {
                    findings.add(new Finding(REPO.relativize(file) + ":" + (i + 1), what));
                }
            }
        }
        return findings;
    }

    /** Property names of JSON Schemas (events, webhooks) named like card data. */
    public static List<Finding> jsonSchemaProperties(List<Path> dirs) {
        var mapper = JsonMapper.builder().build();
        var findings = new ArrayList<Finding>();
        for (var file : files(dirs, p -> p.toString().endsWith(".json"))) {
            walkJson(mapper.readTree(read(file)), "", REPO.relativize(file).toString(), findings);
        }
        return findings;
    }

    /** Property and parameter names of OpenAPI documents named like card data (requests and responses alike). */
    public static List<Finding> openApiNames(Path dir) {
        var options = new LoaderOptions();
        options.setCodePointLimit(64 * 1024 * 1024);
        var findings = new ArrayList<Finding>();
        for (var file : files(List.of(dir), p -> p.toString().endsWith(".yaml"))) {
            Object doc = new Yaml(options).load(read(file));
            walkYaml(doc, "", REPO.relativize(file).toString(), findings);
        }
        return findings;
    }

    /**
     * Web and mobile sources that would take card entry outside Stripe: an input with a card autofill hint
     * ({@code autoComplete="cc-number"}, {@code textContentType="creditCardNumber"}), a form field named like card
     * data, or a script loaded from anywhere but Stripe.
     */
    public static List<Finding> clientSources(List<Path> dirs) {
        var autofill = Pattern.compile("(?i)auto[cC]omplete\\s*[=:]\\s*[{\"'`]*\\s*['\"]?cc-(number|csc|exp)"
                + "|textContentType\\s*[=:]\\s*[{\"'`]*\\s*['\"]?creditCard");
        var fieldName = Pattern.compile("\\b(?:name|id)\\s*=\\s*[{]?['\"]([A-Za-z_-]+)['\"]");
        var script = Pattern.compile("\\.src\\s*=\\s*['\"`](https?://[^'\"`/]+)");
        var findings = new ArrayList<Finding>();
        Predicate<Path> code = p -> p.toString().matches(".*\\.(tsx?|jsx?|mjs|html)$")
                && !p.toString().matches(".*\\.(test|spec|stories)\\.[a-z]+$")
                && !p.toString().contains("node_modules")
                && !p.toString().contains("/dist/");
        for (var file : files(dirs, code)) {
            var lines = read(file).lines().toList();
            for (var i = 0; i < lines.size(); i++) {
                var line = lines.get(i);
                var where = REPO.relativize(file) + ":" + (i + 1);
                if (autofill.matcher(line).find()) {
                    findings.add(new Finding(where, "card autofill hint on an input"));
                }
                var names = fieldName.matcher(line);
                while (names.find()) {
                    if (CardData.isCardDataName(names.group(1))) {
                        findings.add(new Finding(where, "form field named " + names.group(1)));
                    }
                }
                var src = script.matcher(line);
                while (src.find()) {
                    if (!src.group(1).endsWith(".stripe.com")) {
                        findings.add(new Finding(where, "script loaded from " + src.group(1)));
                    }
                }
            }
        }
        return findings;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    static @Nullable String classify(String value, boolean barcode) {
        if (!barcode) {
            var pans = CardData.findPans(value);
            if (!pans.isEmpty()) {
                var pan = pans.getFirst();
                var near = value.substring(Math.max(0, pan.start() - 40), Math.min(value.length(), pan.end() + 40));
                return "PAN " + pan.masked() + (EXPIRY.matcher(near).find() ? " with an expiry date" : "");
            }
        }
        if (CardData.containsTrackData(value)) {
            return "magnetic-stripe track data";
        }
        if (CardData.containsVerificationCode(value)) {
            return "card verification code";
        }
        return null;
    }

    private static void walkJson(JsonNode node, String pointer, String file, List<Finding> findings) {
        if (node.isObject()) {
            var properties = node.get("properties");
            if (properties != null && properties.isObject()) {
                for (var name : properties.propertyNames()) {
                    if (CardData.isCardDataName(name)) {
                        findings.add(new Finding(file + "#" + pointer + "/properties/" + name, "schema property"));
                    }
                }
            }
            for (var entry : node.properties()) {
                walkJson(entry.getValue(), pointer + "/" + entry.getKey(), file, findings);
            }
        } else if (node.isArray()) {
            for (var i = 0; i < node.size(); i++) {
                walkJson(node.get(i), pointer + "/" + i, file, findings);
            }
        }
    }

    private static void walkYaml(Object node, String pointer, String file, List<Finding> findings) {
        if (node instanceof Map<?, ?> map) {
            if (map.get("properties") instanceof Map<?, ?> properties) {
                for (var name : properties.keySet()) {
                    if (CardData.isCardDataName(String.valueOf(name))) {
                        findings.add(new Finding(file + "#" + pointer + "/properties/" + name, "schema property"));
                    }
                }
            }
            if (map.get("in") != null && map.get("name") instanceof String name && CardData.isCardDataName(name)) {
                findings.add(new Finding(file + "#" + pointer, "parameter " + name));
            }
            for (var entry : map.entrySet()) {
                walkYaml(entry.getValue(), pointer + "/" + entry.getKey(), file, findings);
            }
        } else if (node instanceof List<?> list) {
            for (var i = 0; i < list.size(); i++) {
                walkYaml(list.get(i), pointer + "/" + i, file, findings);
            }
        }
    }

    private static List<Path> files(List<Path> dirs, Predicate<Path> include) {
        var all = new ArrayList<Path>();
        for (var dir : dirs) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(Files::isRegularFile).filter(include).sorted().forEach(all::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return all;
    }

    static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
