package com.lily.builder.cloud;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
@Order(0)
public class CloudTokenFilter extends OncePerRequestFilter {
    private static final int LIMIT = 262144;
    private final byte[] expected;
    public CloudTokenFilter(CloudProperties props) {
        String token = props.apiToken();
        expected = token == null || token.length() < 32 || token.startsWith("replace-") ? null : ("Bearer " + token).getBytes(StandardCharsets.UTF_8);
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getServletPath() + (request.getPathInfo() == null ? "" : request.getPathInfo());
        if (!(path.equals("/api/cloud") || path.startsWith("/api/cloud/"))) return true;
        // 등록 때 자동 선택은 배포와 같은 빌더로 온다. 토큰을 아직 안 넣었으면 그 경로만 막지 않는다.
        return expected == null && (path.equals("/api/cloud/selection") || path.startsWith("/api/cloud/selection/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (expected == null) { reject(response, 503, "cloud_disabled"); return; }
        String supplied = request.getHeader("Authorization");
        if (!MessageDigest.isEqual(expected, supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8))) {
            reject(response, 401, "unauthorized"); return;
        }
        byte[] bytes = request.getInputStream().readNBytes(LIMIT + 1);
        if (bytes.length > LIMIT) { reject(response, 413, "request_too_large"); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                var input = new ByteArrayInputStream(bytes);
                return new ServletInputStream() {
                    @Override public int read() { return input.read(); }
                    @Override public int read(byte[] b, int off, int len) { return input.read(b, off, len); }
                    @Override public boolean isFinished() { return input.available() == 0; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
        }, response);
    }
    private static void reject(HttpServletResponse response, int status, String error) throws IOException {
        response.setStatus(status); response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + error + "\"}");
    }
}
