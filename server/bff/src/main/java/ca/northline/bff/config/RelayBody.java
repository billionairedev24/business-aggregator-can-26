package ca.northline.bff.config;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.jspecify.annotations.Nullable;
import org.springframework.web.servlet.function.ServerRequest;

/**
 * S-135: the relay forwards a request body only when the browser sent at least one byte.
 *
 * <p>Gateway MVC streams a body whenever the servlet input stream is not {@code isFinished()}. That is true of an empty
 * chunked request on Tomcat, and of every MockMvc request. Spring's JDK request then publishes an empty body that
 * completes on its own thread without waiting for demand. On a pooled connection the api had already closed, the JDK
 * HttpClient (25) could run that completion before {@code Http1Exchange.sendBodyAsync} had stored its subscriber,
 * and the request failed with an NPE in {@code Http1Exchange.requestMoreBody} (DECISIONS, S-135).
 *
 * <p>This wrapper makes {@code isFinished()} truthful by reading one byte ahead. With no byte, the relay sends no body,
 * so a GET is a plain GET and an empty POST is sent with {@code Content-Length: 0}. With at least one byte, the
 * publisher waits for the client's demand before it signals anything. The look-ahead blocks, which is fine here: the
 * relay is blocking (virtual threads) and reads the body straight after.
 */
final class RelayBody {

    private RelayBody() {}

    /** Gateway {@code before} filter; put it first, so later filters keep the wrapped servlet request. */
    static ServerRequest lookAhead(ServerRequest request) {
        return ServerRequest.create(new LookAheadRequest(request.servletRequest()), request.messageConverters());
    }

    private static final class LookAheadRequest extends HttpServletRequestWrapper {

        private @Nullable LookAheadInputStream body;

        LookAheadRequest(HttpServletRequest request) {
            super(request);
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (body == null) {
                body = new LookAheadInputStream(super.getInputStream());
            }
            return body;
        }
    }

    private static final class LookAheadInputStream extends ServletInputStream {

        private static final int UNREAD = -2;
        private static final int EOF = -1;
        private static final int PASSED_ON = -3;

        private final ServletInputStream in;
        /** {@link #UNREAD}, {@link #EOF}, {@link #PASSED_ON} (the byte was returned) or the byte read ahead. */
        private int next = UNREAD;

        LookAheadInputStream(ServletInputStream in) {
            this.in = in;
        }

        @Override
        public boolean isFinished() {
            if (next == UNREAD) {
                try {
                    next = in.isFinished() ? EOF : in.read();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return next == EOF || (next == PASSED_ON && in.isFinished());
        }

        @Override
        public boolean isReady() {
            return next >= 0 || next == EOF || in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }

        @Override
        public int read() throws IOException {
            if (next >= 0) {
                var b = next;
                next = PASSED_ON;
                return b;
            }
            return next == EOF ? EOF : in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) {
                return 0;
            }
            if (next >= 0) {
                b[off] = (byte) next;
                next = PASSED_ON;
                return 1;
            }
            return next == EOF ? EOF : in.read(b, off, len);
        }

        @Override
        public int available() throws IOException {
            return next >= 0 ? 1 : next == EOF ? 0 : in.available();
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
