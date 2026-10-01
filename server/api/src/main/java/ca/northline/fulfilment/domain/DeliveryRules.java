package ca.northline.fulfilment.domain;

import java.util.Set;
import java.util.random.RandomGenerator;

/** Rules and messages of the courier's stops (S-86). English on the server; fr-CA in the message catalogue. */
public final class DeliveryRules {
    private DeliveryRules() {}

    public static final String NOT_PACKED = "This shop hasn't packed the order yet.";
    public static final String NOT_PICKED_UP = "Pick the order up from every shop before dropping it off.";
    public static final String STOP_DONE = "This stop is already done.";
    public static final String PIN_WRONG = "That PIN doesn't match. Ask the customer for the 4 digits on their order.";
    public static final String PIN_REQUIRED = "Enter the customer's 4-digit PIN.";
    public static final String PROOF_REQUIRED = "Choose photo, signature or PIN as proof.";
    public static final String PROOF_MISSING = "Upload the photo or signature first.";
    public static final String PROOF_FILE = "Upload a JPG, PNG or WebP image under 5 MB.";
    public static final String NOT_YOUR_RUN = "This stop isn't on your run.";
    public static final String SHIFT_NOT_STARTABLE = "This shift can't be started now.";
    public static final String NOT_ON_SHIFT = "Start your shift to share your position.";
    public static final String POSITION = "Send a latitude and longitude on the map.";
    public static final String RUN_OPEN = "Finish your run before ending the shift.";
    public static final String NOT_A_COURIER = "This account isn't a Northline courier.";
    public static final String COURIER_BUSY = "This courier isn't available.";
    public static final String COURIER_REQUIRED = "Choose a courier.";
    public static final String PERSON_REQUIRED = "Choose a person.";
    public static final String RUN_STARTED = "This run has started; it can't be reassigned.";
    public static final String MARKET_REQUIRED = "Choose a market.";
    public static final String VEHICLE = "Choose bike, ebike, car or van.";
    public static final String SHIFT_TIMES = "A shift ends after it starts and lasts at most 12 hours.";
    public static final String ALREADY_A_COURIER = "This person is already a courier.";
    public static final String PAUSE_REASON = "Say why the courier is paused.";
    public static final String PAUSE_REASON_LENGTH = "Keep the reason under 500 characters.";

    public static final Set<String> PROOFS = Set.of("photo", "signature", "pin");
    public static final Set<String> VEHICLES = Set.of("bike", "ebike", "car", "van");
    public static final long MAX_PROOF_BYTES = 5L * 1024 * 1024;

    /** A 4-digit drop-off PIN. */
    public static String pin(RandomGenerator random) {
        return String.format(java.util.Locale.ROOT, "%04d", random.nextInt(10_000));
    }

    /** The image type from the file's first bytes (not the name or the header the client sent), else null. */
    public static @org.jspecify.annotations.Nullable String imageType(byte[] bytes) {
        if (bytes.length > 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (bytes.length > 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
            return "image/png";
        }
        if (bytes.length > 12
                && bytes[0] == 'R'
                && bytes[1] == 'I'
                && bytes[2] == 'F'
                && bytes[3] == 'F'
                && bytes[8] == 'W'
                && bytes[9] == 'E'
                && bytes[10] == 'B'
                && bytes[11] == 'P') {
            return "image/webp";
        }
        return null;
    }
}
