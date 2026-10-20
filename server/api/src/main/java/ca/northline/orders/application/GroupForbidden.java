package ca.northline.orders.application;

/**
 * S-159: a group order member asked for something only the host (or the share's owner) may do — 403 ProblemDetail
 * with {@code code} {@code not_host} | {@code not_your_share} | {@code not_yours}.
 */
public final class GroupForbidden extends RuntimeException {

    private final String code;

    GroupForbidden(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
