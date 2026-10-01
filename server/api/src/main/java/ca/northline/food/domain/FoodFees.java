package ca.northline.food.domain;

/**
 * What a food order costs besides the dishes, and how long it takes (design 06 `food` / `restaurant`): the direct hot
 * courier's fee by distance ($1.99 within 1 km, $2.99 within 2 km, $3.99 within 4 km, +$1 per further 2 km; $2.99 while
 * the distance isn't known), an 8 % service fee, a $15 minimum, and the ETA = the kitchen's promised prep + the ride.
 */
public final class FoodFees {
    private FoodFees() {}

    public static final long MIN_ORDER_CENTS = 1_500;
    public static final int SERVICE_FEE_BPS = 800;
    public static final long UNKNOWN_DISTANCE_FEE_CENTS = 299;
    /** When a kitchen set no delivery radius. */
    public static final double DEFAULT_RADIUS_KM = 8;

    public static long deliveryFeeCents(double km) {
        if (km <= 1) {
            return 199;
        }
        if (km <= 2) {
            return 299;
        }
        if (km <= 4) {
            return 399;
        }
        return 399 + 100 * (long) Math.ceil((km - 4) / 2);
    }

    public static long serviceFeeCents(long itemsCents) {
        return Math.round(itemsCents * SERVICE_FEE_BPS / 10_000.0);
    }

    /** Minutes on an e-bike in the city: 3 per km plus 5 to hand over; 10 when the distance isn't known. */
    public static int rideMinutes(double km) {
        return (int) Math.ceil(km * 3) + 5;
    }

    /** "$", "$$", "$$$" from the average dish price. */
    public static String priceLevel(long averageCents) {
        return averageCents < 1_200 ? "$" : averageCents < 2_500 ? "$$" : "$$$";
    }

    /** Great-circle distance in km (haversine), rounded to 0.1. */
    public static double km(double lat1, double lng1, double lat2, double lng2) {
        var dLat = Math.toRadians(lat2 - lat1);
        var dLng = Math.toRadians(lng2 - lng1);
        var h = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.pow(Math.sin(dLng / 2), 2);
        return Math.round(2 * 6371 * Math.asin(Math.sqrt(h)) * 10) / 10.0;
    }
}
