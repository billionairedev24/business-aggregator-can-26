package ca.northline.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** S-104: bodies over the cap are refused before (declared length) or while (chunked) they are read. */
class RequestSizeLimitFilterTest {

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(1_000, 5_000);

    @Test
    void aDeclaredLengthOverTheCap_is413_andTheAppNeverSeesIt() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/webhooks/stripe");
        request.setContentType("application/json");
        request.setContent(new byte[1_001]);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"code\":\"payload_too_large\"");
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void aBodyWithoutALength_failsWhileReadingPastTheCap() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/cart") {
            @Override
            public long getContentLengthLong() {
                return -1; // chunked
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setContentType("application/json");
        request.setContent(new byte[1_500]);
        var seen = new AtomicReference<HttpServletRequest>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, _) -> seen.set((HttpServletRequest) req));

        assertThatThrownBy(() -> seen.get().getInputStream().readAllBytes())
                .isInstanceOf(RequestSizeLimitFilter.PayloadTooLarge.class);
    }

    @Test
    void bodiesWithinTheCap_andLargerUploads_passUnchanged() throws Exception {
        var json = new MockHttpServletRequest("POST", "/api/v1/cart");
        json.setContentType("application/json");
        json.setContent(new byte[1_000]);
        var seen = new AtomicReference<HttpServletRequest>();
        filter.doFilter(json, new MockHttpServletResponse(), (req, _) -> seen.set((HttpServletRequest) req));
        assertThat(read(seen.get())).isEqualTo(1_000);

        var upload = new MockHttpServletRequest("POST", "/api/v1/me/case-uploads");
        upload.setContentType("multipart/form-data; boundary=x");
        upload.setContent(new byte[4_000]);
        var response = new MockHttpServletResponse();
        filter.doFilter(upload, response, (req, _) -> seen.set((HttpServletRequest) req));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(read(seen.get())).isEqualTo(4_000);
    }

    private static int read(HttpServletRequest request) throws IOException {
        return request.getInputStream().readAllBytes().length;
    }
}
