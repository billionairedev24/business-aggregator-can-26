package ca.northline.auth.web;

import ca.northline.auth.application.FlowStore;
import jakarta.servlet.http.HttpSession;
import java.io.Serializable;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** {@link FlowStore} on the auth server's HTTP session ({@code HttpSession} is a request-scoped proxy). */
@Component
@RequiredArgsConstructor
class HttpSessionFlowStore implements FlowStore {

    private final HttpSession session;

    @Override
    public <T extends Serializable> Optional<T> get(Key<T> key) {
        var value = session.getAttribute(key.name());
        return key.type().isInstance(value) ? Optional.of(key.type().cast(value)) : Optional.empty();
    }

    @Override
    public <T extends Serializable> void put(Key<T> key, T value) {
        session.setAttribute(key.name(), value);
    }

    @Override
    public void remove(Key<?> key) {
        session.removeAttribute(key.name());
    }
}
