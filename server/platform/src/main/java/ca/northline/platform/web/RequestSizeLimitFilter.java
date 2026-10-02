package ca.northline.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * S-104: no request body larger than the app accepts is read into memory. Spring reads a {@code @RequestBody} — a
 * Stripe webhook's payload, a guest's cart JSON — whole, and neither Tomcat nor the edge capped it, so one anonymous
 * request of a few gigabytes could exhaust a pod's heap.
 *
 * <p>A declared {@code Content-Length} over the cap is answered {@code 413} at once; a body without one (chunked) is
 * counted while it is read and fails with {@link PayloadTooLarge} at the cap. Uploads (multipart, or a raw file body)
 * get their own, larger cap (the endpoints' own size limits still apply inside it).
 */
public class RequestSizeLimitFilter extends OncePerRequestFilter implements Ordered {

    /** Thrown while reading a body that turns out to be over the cap. */
    public static final class PayloadTooLarge extends IOException {
        private static final long serialVersionUID = 1L;

        PayloadTooLarge(long cap) {
            super("Request body over " + cap + " bytes");
        }
    }

    private final long maxBody;
    private final long maxMultipart;

    public RequestSizeLimitFilter(long maxBody, long maxMultipart) {
        this.maxBody = maxBody;
        this.maxMultipart = Math.max(maxBody, maxMultipart);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 5;
    }

    /**
     * Structured bodies (JSON, text, XML, forms) that the app parses into memory get the small cap; uploads — multipart
     * and raw files such as a refund's evidence PDF — the upload cap (their own per-endpoint limits apply inside it).
     */
    long capFor(HttpServletRequest request) {
        var type = request.getContentType();
        if (type == null) {
            return maxBody;
        }
        var t = type.toLowerCase(Locale.ROOT);
        var parsed = t.startsWith("application/json")
                || t.contains("+json")
                || t.startsWith("text/")
                || t.startsWith("application/xml")
                || t.contains("+xml")
                || t.startsWith("application/x-www-form-urlencoded");
        return parsed ? maxBody : maxMultipart;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var cap = capFor(request);
        if (request.getContentLengthLong() > cap) {
            response.setStatus(413);
            response.setContentType("application/problem+json");
            response.setHeader("Connection", "close");
            response.getWriter().write("""
                    {"type":"https://northline.ca/problems/payload-too-large","title":"Payload Too Large",\
                    "status":413,"detail":"This request is too large.","code":"payload_too_large"}""");
            return;
        }
        chain.doFilter(new Capped(request, cap), response);
    }

    /** The request with its body stream counted against the cap. */
    static final class Capped extends HttpServletRequestWrapper {
        private final long cap;
        private @Nullable ServletInputStream stream;

        Capped(HttpServletRequest request, long cap) {
            super(request);
            this.cap = cap;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) {
                stream = new CountingStream(super.getInputStream(), cap);
            }
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            var encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class CountingStream extends ServletInputStream {
        private final ServletInputStream in;
        private final long cap;
        private long read;

        CountingStream(ServletInputStream in, long cap) {
            this.in = in;
            this.cap = cap;
        }

        @Override
        public int read() throws IOException {
            var b = in.read();
            if (b >= 0) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            var n = in.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) throws PayloadTooLarge {
            read += n;
            if (read > cap) {
                throw new PayloadTooLarge(cap);
            }
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
