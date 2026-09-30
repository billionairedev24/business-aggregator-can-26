package ca.northline.auth.support;

import org.testcontainers.containers.GenericContainer;

/** One Valkey 8 container per test JVM (rate limits, S-9). Tests use their own keys, so it is never flushed. */
public final class SharedValkey {

    @SuppressWarnings("resource")
    public static final GenericContainer<?> INSTANCE = new GenericContainer<>("valkey/valkey:8").withExposedPorts(6379);

    static {
        INSTANCE.start();
    }

    private SharedValkey() {}

    public static String host() {
        return INSTANCE.getHost();
    }

    public static int port() {
        return INSTANCE.getMappedPort(6379);
    }
}
