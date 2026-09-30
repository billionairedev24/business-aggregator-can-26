package ca.northline.catalogue.fixture;

/** Test fixture for {@code SchemaOwnershipDetectorTest}: SQL shapes the S-37 rule must catch (or not). */
@SuppressWarnings("unused")
public final class CrossSchemaSqlFixture {

    private CrossSchemaSqlFixture() {}

    static final String OWN = "select id from catalogue.offers where merchant_id = ?";

    static String textBlock() {
        return """
                select o.id from catalogue.offers o
                  join merchants.merchants m on m.id = o.merchant_id
                """;
    }

    static String concatenated(String column) {
        return "select " + column + " from payments.escrows where id = ?";
    }

    /** An event type, not SQL: never flagged. */
    static final String EVENT = "orders.order_ready";
}
