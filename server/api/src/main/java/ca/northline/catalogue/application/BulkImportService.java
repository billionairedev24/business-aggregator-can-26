package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.AutomatedVetting;
import ca.northline.catalogue.domain.CategoryProfile;
import ca.northline.catalogue.domain.Fulfilment;
import ca.northline.catalogue.domain.Gtin;
import ca.northline.catalogue.domain.IdentifierType;
import ca.northline.catalogue.domain.ImageSource;
import ca.northline.catalogue.domain.ImportBatch;
import ca.northline.catalogue.domain.ImportBatch.RowError;
import ca.northline.catalogue.domain.ImportBatch.ValidRow;
import ca.northline.catalogue.domain.ImportTemplate;
import ca.northline.catalogue.domain.ItemCondition;
import ca.northline.catalogue.domain.Listing;
import ca.northline.catalogue.domain.ListingKind;
import ca.northline.catalogue.domain.PricingMode;
import ca.northline.catalogue.domain.ProductDetails;
import ca.northline.catalogue.domain.ProductDetails.Variant;
import ca.northline.catalogue.domain.ProductListing;
import ca.northline.catalogue.domain.ServiceDetails;
import ca.northline.catalogue.domain.ServiceListing;
import ca.northline.catalogue.domain.VariantTheme;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk upload. {@link #validate} reads the file and checks every row — nothing changes yet; errors come back with
 * row numbers. {@link #commit} then creates new SKUs as drafts and updates price and stock of existing SKUs.
 */
@Service
@RequiredArgsConstructor
@Transactional
class BulkImportService implements BulkImport {

    static final int MAX_ROWS = 10_000;
    static final String UNREADABLE = "Upload an .xlsx or .csv file.";
    static final String EMPTY = "The file has no rows.";
    static final String TOO_MANY = "Up to 10,000 rows per file.";

    private final SpreadsheetReader reader;
    private final ImportRepository imports;
    private final ListingRepository listings;
    private final CategoryCatalog categories;
    private final EditProduct editProduct;
    private final EditService editService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // ── validate ───────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public ImportBatch validate(
            String merchantId, ImportTemplate template, String fileName, byte[] bytes, String actorId) {
        List<Map<String, String>> rows;
        try {
            rows = reader.read(fileName, bytes);
        } catch (SpreadsheetReader.UnreadableFile ex) {
            throw RuleViolation.of("file", "format", UNREADABLE);
        }
        rows = rows.stream()
                .filter(r -> r.values().stream().anyMatch(v -> !v.isBlank()))
                .toList();
        if (rows.isEmpty()) {
            throw RuleViolation.of("file", "required", EMPTY);
        }
        if (rows.size() > MAX_ROWS) {
            throw RuleViolation.of("file", "rows", TOO_MANY);
        }
        var errors = new ArrayList<RowError>();
        var valid = new ArrayList<ValidRow>();
        var seen = new HashSet<String>();
        var profiles = new HashMap<String, Optional<CategoryProfile>>();
        for (int i = 0; i < rows.size(); i++) {
            var row = new RowCheck(merchantId, template, i + 2, rows.get(i), seen, profiles);
            row.check().ifPresentOrElse(errors::add, () -> valid.add(row.toValid()));
        }
        var updates = (int) valid.stream().filter(ValidRow::update).count();
        var batch = new ImportBatch(
                Ids.next(),
                merchantId,
                fileName,
                template,
                rows.size(),
                valid.size() - updates,
                updates,
                errors,
                valid,
                ImportBatch.Status.VALIDATED,
                actorId,
                clock.instant(),
                null);
        imports.insert(batch);
        return batch;
    }

    /** Validation of one spreadsheet row; the first problem wins (one error per row in the report). */
    private final class RowCheck {
        private final String merchantId;
        private final ImportTemplate template;
        private final int rowNumber;
        private final Map<String, String> raw;
        private final java.util.Set<String> seen;
        private final Map<String, Optional<CategoryProfile>> profiles;
        private final Map<String, String> values = new LinkedHashMap<>();
        private String sku = "";
        private boolean update;

        RowCheck(
                String merchantId,
                ImportTemplate template,
                int rowNumber,
                Map<String, String> raw,
                java.util.Set<String> seen,
                Map<String, Optional<CategoryProfile>> profiles) {
            this.merchantId = merchantId;
            this.template = template;
            this.rowNumber = rowNumber;
            this.raw = raw;
            this.seen = seen;
            this.profiles = profiles;
        }

        ValidRow toValid() {
            return new ValidRow(rowNumber, sku, update, values);
        }

        Optional<RowError> check() {
            sku = cell("sku");
            if (sku.isEmpty()) {
                return fail("Missing SKU");
            }
            if (!seen.add(sku.toLowerCase(Locale.ROOT))) {
                return fail("Duplicate SKU in file");
            }
            values.put("sku", sku);
            var existing = listings.bySku(merchantId, sku);
            update = existing.isPresent();
            return switch (template) {
                case PRICE_STOCK -> checkQuickUpdate(existing.orElse(null));
                case SERVICES ->
                    existing.isPresent() && existing.get().kind() != ListingKind.SERVICE
                            ? fail("SKU belongs to a product")
                            : checkService();
                case AUTO_PARTS, GROCERIES, CLOTHING ->
                    existing.isPresent() && existing.get().kind() != ListingKind.PRODUCT
                            ? fail("SKU belongs to a service")
                            : checkProduct();
            };
        }

        private Optional<RowError> checkQuickUpdate(@Nullable Listing existing) {
            if (existing == null) {
                return fail("Unknown SKU — add it with a category template");
            }
            var price = cell("price");
            var stock = cell("stock");
            if (price.isEmpty() && stock.isEmpty()) {
                return fail("Missing price and stock");
            }
            if (!price.isEmpty()) {
                var cents = money(price);
                if (cents.isEmpty()) {
                    return fail("Price must be an amount, e.g. 19.99");
                }
                values.put("price", Long.toString(cents.get()));
            }
            if (!stock.isEmpty()) {
                var units = wholeNumber(stock);
                if (units.isEmpty()) {
                    return fail("Stock must be a whole number");
                }
                values.put("stock", Integer.toString(units.get()));
            }
            return Optional.empty();
        }

        private Optional<RowError> checkService() {
            var name = cell("name");
            if (name.isEmpty() && !update) {
                return fail("Missing name");
            }
            values.put("name", name);
            var category = category(cell("category_id"));
            if (category.isEmpty() && !cell("category_id").isEmpty()) {
                return fail("Unknown category");
            }
            if (category.isPresent()
                    && (!category.get().isService() || !category.get().leaf())) {
                return fail("Category not allowed in Services");
            }
            category.ifPresent(c -> values.put("category_id", c.id()));
            var mode = cell("pricing_mode").isEmpty()
                    ? "fixed"
                    : cell("pricing_mode").toLowerCase(Locale.ROOT);
            if (!List.of("fixed", "quote", "hourly").contains(mode)) {
                return fail("Pricing mode must be fixed, quote or hourly");
            }
            values.put("pricing_mode", mode);
            if (!"quote".equals(mode)) {
                var problem = price(category.orElse(null));
                if (problem.isPresent()) {
                    return problem;
                }
            }
            var duration = cell("duration_min").isEmpty() ? Optional.of(60) : wholeNumber(cell("duration_min"));
            if (duration.isEmpty() || duration.get() < 15 || duration.get() > 720) {
                return fail("Duration must be 15–720 minutes");
            }
            values.put("duration_min", duration.get().toString());
            var buffer = cell("buffer_min").isEmpty() ? Optional.of(0) : wholeNumber(cell("buffer_min"));
            if (buffer.isEmpty() || buffer.get() > 120) {
                return fail("Buffer must be 0–120 minutes");
            }
            values.put("buffer_min", buffer.get().toString());
            values.put("included", cell("included"));
            var instant = cell("instant_book").toLowerCase(Locale.ROOT);
            values.put(
                    "instant_book",
                    Boolean.toString(!List.of("no", "false", "0", "n").contains(instant)));
            return Optional.empty();
        }

        private Optional<RowError> checkProduct() {
            var parent = cell("parent_sku");
            if (!parent.isEmpty()) {
                if (listings.bySku(merchantId, parent).isPresent()) {
                    return fail("Parent SKU already exists — edit its variants in the product editor");
                }
                if (update) {
                    return fail("SKU already exists");
                }
                values.put("parent_sku", parent);
            }
            var title = cell("title");
            if (title.isEmpty() && !update) {
                return fail("Missing title");
            }
            values.put("title", title);
            var gtin = cell("gtin");
            if (!gtin.isEmpty()) {
                var problem = Gtin.problem("gtin", gtin);
                if (problem.isPresent()) {
                    return fail(problem.get().message());
                }
                values.put("gtin", Gtin.normalize(gtin));
            }
            values.put("brand", cell("brand"));
            values.put("mpn", cell("mpn"));
            var categoryId = cell("category_id").isEmpty()
                    ? java.util.Objects.requireNonNullElse(template.defaultCategoryId(), "")
                    : cell("category_id");
            var category = category(categoryId);
            if (category.isEmpty()) {
                return fail("Unknown category");
            }
            var cat = category.get();
            if (!cat.isShop() || !cat.leaf() || cat.banned()) {
                return fail("Category not allowed in Shop");
            }
            values.put("category_id", cat.id());
            if (!update) {
                for (var spec : cat.attributes()) {
                    var column = snake(spec.key());
                    var value = cell(column);
                    if (value.isEmpty()) {
                        if (spec.required()) {
                            return fail("Category \"%s\" requires attribute %s".formatted(cat.name(), spec.label()));
                        }
                        continue;
                    }
                    var option = spec.options().stream()
                            .filter(o -> o.equalsIgnoreCase(value))
                            .findFirst();
                    if (option.isEmpty()) {
                        return fail("%s must be one of %s".formatted(spec.label(), String.join(", ", spec.options())));
                    }
                    values.put("attr." + spec.key(), option.get());
                }
            }
            var priceProblem = price(cat);
            if (priceProblem.isPresent()) {
                return priceProblem;
            }
            var stock = cell("stock");
            var units = wholeNumber(stock);
            if (stock.isEmpty() || units.isEmpty()) {
                return fail(stock.isEmpty() ? "Missing stock" : "Stock must be a whole number");
            }
            values.put("stock", units.get().toString());
            values.put("size", cell("size"));
            values.put("colour", cell("colour"));
            return Optional.empty();
        }

        private Optional<RowError> price(@Nullable CategoryProfile category) {
            var price = cell("price");
            if (price.isEmpty()) {
                return fail("Missing price");
            }
            var cents = money(price);
            if (cents.isEmpty() || cents.get() <= 0) {
                return fail("Price must be an amount, e.g. 19.99");
            }
            var median = category == null ? null : category.medianPriceCents();
            if (median != null && AutomatedVetting.isOutlier(cents.get(), median)) {
                var pct = AutomatedVetting.deviationPercent(cents.get(), median);
                return fail("Price %s is %d%% %s category median — confirm"
                        .formatted(dollars(cents.get()), Math.abs(pct), pct < 0 ? "below" : "above"));
            }
            values.put("price", cents.get().toString());
            return Optional.empty();
        }

        private Optional<CategoryProfile> category(String id) {
            return id.isEmpty() ? Optional.empty() : profiles.computeIfAbsent(id, categories::profile);
        }

        private String cell(String column) {
            return raw.getOrDefault(column, "").strip();
        }

        private Optional<RowError> fail(String message) {
            return Optional.of(new RowError(rowNumber, sku.isEmpty() ? null : sku, message));
        }
    }

    // ── commit ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public ImportBatch commit(String merchantId, String importId, String actorId) {
        var batch = imports.find(merchantId, importId).orElseThrow(() -> new NotFound("import", importId));
        var done = batch.imported(clock.instant());
        var now = clock.instant();
        var creates = new LinkedHashMap<String, List<ValidRow>>();
        for (var row : batch.pendingRows()) {
            if (row.update()) {
                applyUpdate(merchantId, row, actorId, now);
            } else {
                creates.computeIfAbsent(row.values().getOrDefault("parent_sku", row.sku()), _ -> new ArrayList<>())
                        .add(row);
            }
        }
        creates.forEach((sku, rows) -> {
            if (batch.template() == ImportTemplate.SERVICES) {
                editService.create(new EditService.Command(merchantId, null, service(rows.getFirst()), actorId));
            } else {
                editProduct.create(new EditProduct.Command(merchantId, null, product(sku, rows), actorId));
            }
        });
        imports.update(done);
        return done;
    }

    /** Existing SKU: price and stock only. A new price on an approved listing sends it back to vetting (S-39). */
    private void applyUpdate(String merchantId, ValidRow row, String actorId, java.time.Instant now) {
        var listing = listings.bySku(merchantId, row.sku()).orElseThrow(() -> new NotFound("listing", row.sku()));
        var price = row.values().get("price");
        var stock = row.values().get("stock");
        switch (listing) {
            case ProductListing p -> {
                var revet = p.restock(
                        price == null ? p.getDetails().priceCents() : Long.parseLong(price),
                        stock == null ? p.getDetails().stock() : Integer.parseInt(stock),
                        actorId,
                        now);
                listings.save(p);
                revet.forEach(events::publishEvent);
            }
            case ServiceListing s -> {
                if (price != null) {
                    var revet = s.reprice(Long.parseLong(price), actorId, now);
                    listings.save(s);
                    revet.forEach(events::publishEvent);
                }
            }
        }
    }

    private static ServiceDetails service(ValidRow row) {
        var v = row.values();
        var mode = ca.northline.shared.CodedEnum.fromCode(PricingMode.class, v.getOrDefault("pricing_mode", "fixed"));
        var price = v.get("price");
        return new ServiceDetails(
                v.getOrDefault("name", ""),
                blankToNull(v.get("category_id")),
                mode,
                price == null ? null : Long.parseLong(price),
                Integer.parseInt(v.getOrDefault("duration_min", "60")),
                Integer.parseInt(v.getOrDefault("buffer_min", "0")),
                blankToNull(v.get("included")),
                Boolean.parseBoolean(v.getOrDefault("instant_book", "true")),
                row.sku());
    }

    private static ProductDetails product(String sku, List<ValidRow> rows) {
        var first = rows.getFirst().values();
        var grouped = first.containsKey("parent_sku");
        var attributes = new HashMap<String, String>();
        first.forEach((k, val) -> {
            if (k.startsWith("attr.")) {
                attributes.put(k.substring(5), val);
            }
        });
        var hasSize =
                rows.stream().anyMatch(r -> !r.values().getOrDefault("size", "").isEmpty());
        var hasColour = rows.stream()
                .anyMatch(r -> !r.values().getOrDefault("colour", "").isEmpty());
        var theme = !grouped
                ? VariantTheme.NONE
                : hasSize && hasColour ? VariantTheme.SIZE_COLOUR : hasColour ? VariantTheme.COLOUR : VariantTheme.SIZE;
        var variants = grouped
                ? rows.stream()
                        .map(r -> new Variant(
                                null,
                                variantName(r.values()),
                                r.sku(),
                                blankToNull(r.values().get("gtin")),
                                Long.parseLong(r.values().getOrDefault("price", "0")),
                                Integer.parseInt(r.values().getOrDefault("stock", "0"))))
                        .toList()
                : List.<Variant>of();
        var gtin = grouped ? null : blankToNull(first.get("gtin"));
        return new ProductDetails(
                gtin == null ? IdentifierType.NONE : IdentifierType.GTIN,
                gtin,
                first.getOrDefault("title", ""),
                blankToNull(first.get("brand")),
                blankToNull(first.get("mpn")),
                blankToNull(first.get("category_id")),
                attributes,
                null,
                List.of(),
                theme,
                variants,
                gtin == null ? ImageSource.OWN : ImageSource.SHARED,
                List.of(),
                sku,
                Long.parseLong(first.getOrDefault("price", "0")),
                null,
                null,
                ItemCondition.NEW,
                rows.stream()
                        .mapToInt(r -> Integer.parseInt(r.values().getOrDefault("stock", "0")))
                        .sum(),
                null,
                List.of(Fulfilment.POOLED),
                null,
                null,
                null,
                false,
                false,
                false,
                null);
    }

    private static String variantName(Map<String, String> v) {
        var parts = new ArrayList<String>();
        for (var key : List.of("size", "colour")) {
            var val = v.getOrDefault(key, "");
            if (!val.isEmpty()) {
                parts.add(val);
            }
        }
        return parts.isEmpty() ? v.getOrDefault("sku", "") : String.join(" · ", parts);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ImportBatch> history(String merchantId) {
        return imports.history(merchantId, 50);
    }

    // ── parsing helpers ────────────────────────────────────────────────────────────────────────────────────────────

    static Optional<Long> money(String text) {
        try {
            var amount = new BigDecimal(text.replace("$", "").replace(",", "").strip());
            return amount.scale() > 2 || amount.signum() < 0
                    ? Optional.empty()
                    : Optional.of(amount.movePointRight(2).longValueExact());
        } catch (NumberFormatException | ArithmeticException ex) {
            return Optional.empty();
        }
    }

    static Optional<Integer> wholeNumber(String text) {
        try {
            var n = new BigDecimal(text.strip());
            return n.signum() < 0 || n.stripTrailingZeros().scale() > 0
                    ? Optional.empty()
                    : Optional.of(n.intValueExact());
        } catch (NumberFormatException | ArithmeticException ex) {
            return Optional.empty();
        }
    }

    static String dollars(long cents) {
        return cents % 100 == 0 ? "$" + cents / 100 : "$%d.%02d".formatted(cents / 100, cents % 100);
    }

    static String snake(String camel) {
        return camel.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    private static @Nullable String blankToNull(@Nullable String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
