package ca.northline.worker.notifications;

import java.util.Locale;

/** The app a push goes to ({@code messaging.push_devices.app}); Studio has no native app, so nothing registers it. */
public enum PushApp {
    CONSUMER,
    COURIER,
    STUDIO;

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }
}
